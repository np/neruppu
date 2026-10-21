/*
 * Copyright (C) 2026 thamizh-root
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.havenapp.neruppu.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import org.havenapp.neruppu.domain.remote.RemoteCommand
import org.havenapp.neruppu.domain.remote.RemoteControlRepository
import org.havenapp.neruppu.domain.remote.RemoteRole
import org.havenapp.neruppu.domain.remote.RemoteUpdate
import org.havenapp.neruppu.domain.repository.EncryptionKeyRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteControlRepositoryImpl @Inject constructor(
    private val config: RemoteConfigStore,
    private val ntfy: NtfyClient,
    private val keyring: AgeKeyring,
    private val encryptionKeys: EncryptionKeyRepository
) : RemoteControlRepository {

    internal var clock: () -> Long = System::currentTimeMillis

    override val role: RemoteRole get() = config.role

    override fun createControllerPairing(server: String): String {
        val identity = keyring.generateIdentity()
        val pairing = RemoteProtocol.Pairing(
            server = server.trim().trimEnd('/'),
            topic = RemoteProtocol.newTopic(),
            key = RemoteProtocol.keyString(RemoteProtocol.newKey()),
            recipient = keyring.recipientOf(identity)
        )
        // Validates the server URL and the rest exactly as the guarded phone will.
        RemoteProtocol.decodePairing(RemoteProtocol.encodePairing(pairing)).getOrThrow()
        config.saveController(pairing, identity)
        return RemoteProtocol.encodePairing(pairing)
    }

    override fun pairingCode(): String? =
        if (config.role == RemoteRole.CONTROLLER) config.pairing?.let(RemoteProtocol::encodePairing) else null

    override fun pairAsGuard(code: String): Result<Unit> = runCatching {
        // A guarded phone must never hold an age identity.
        check(config.role != RemoteRole.CONTROLLER) {
            "This phone is a remote control. Unpair it first to guard with it."
        }
        val pairing = RemoteProtocol.decodePairing(code).getOrThrow()
        require(keyring.isValidRecipient(pairing.recipient)) { "Invalid age key in pairing code" }
        // Captures are encrypted to the remote too, so it can open them.
        val keys = (encryptionKeys.recipients.lines() + pairing.recipient).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        check(encryptionKeys.setRecipients(keys)) { "Could not store the remote's age key" }
        config.saveGuard(pairing)
    }

    override fun unpair() = config.clear()

    override suspend fun send(command: RemoteCommand): Result<Unit> = runCatching {
        val pairing = checkNotNull(config.pairing) { "Not paired" }
        val message = RemoteProtocol.Command(
            id = UUID.randomUUID().toString(),
            ts = clock(),
            cmd = command.name.lowercase()
        )
        val sealed = RemoteProtocol.seal(
            RemoteProtocol.json.encodeToString(message),
            RemoteProtocol.keyBytes(pairing), pairing.topic, RemoteProtocol.Direction.COMMAND
        )
        ntfy.publish(pairing.server, RemoteProtocol.commandTopic(pairing.topic), sealed)
    }

    override fun updates(): Flow<RemoteUpdate> = flow {
        val pairing = config.pairing ?: return@flow
        val identity = config.identity ?: return@flow
        val key = RemoteProtocol.keyBytes(pairing)
        val seen = LinkedHashSet<String>()
        // First connection looks back a little, to catch an answer that raced it.
        var since: String? = "2m"
        var backoffMs = 2_000L
        while (true) {
            try {
                emit(RemoteUpdate.Connected(true))
                ntfy.subscribe(pairing.server, RemoteProtocol.responseTopic(pairing.topic), since).collect { event ->
                    backoffMs = 2_000L
                    since = event.id
                    val text = event.message ?: return@collect
                    val plain = RemoteProtocol.open(text, key, pairing.topic, RemoteProtocol.Direction.RESPONSE)
                        ?: return@collect
                    val response = runCatching {
                        RemoteProtocol.json.decodeFromString(RemoteProtocol.Response.serializer(), plain)
                    }.getOrNull() ?: return@collect
                    if (!seen.add(response.id)) return@collect
                    if (seen.size > 256) seen.remove(seen.first())
                    emit(toUpdate(response, event, identity))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(RemoteUpdate.Connected(false))
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun toUpdate(response: RemoteProtocol.Response, event: NtfyClient.Event, identity: String): RemoteUpdate =
        when (response.kind) {
            "status" -> response.status?.let { RemoteUpdate.Status(response.ts, it.toDomain()) }
                ?: RemoteUpdate.Failure(response.ts, "Empty status")
            "photo" -> runCatching {
                val url = checkNotNull(event.attachment?.url) { "Photo missing (attachment expired?)" }
                RemoteUpdate.Photo(response.ts, keyring.decrypt(ntfy.download(url), identity))
            }.getOrElse { RemoteUpdate.Failure(response.ts, it.message ?: "Photo could not be opened") }
            else -> RemoteUpdate.Failure(response.ts, response.error ?: "Error on the guarded phone")
        }
}
