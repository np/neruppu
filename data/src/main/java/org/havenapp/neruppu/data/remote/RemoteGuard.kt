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

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import org.havenapp.neruppu.data.BuildConfig
import org.havenapp.neruppu.data.crypto.AgeEncryptor
import org.havenapp.neruppu.domain.remote.RemoteRole
import org.havenapp.neruppu.domain.remote.RemoteStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the guarded phone exposes to the remote: its state and a fresh photo. */
interface GuardDevice {
    suspend fun status(): RemoteStatus
    /** Plain JPEG, held in memory only; null if the camera is unavailable. */
    suspend fun takePhoto(): GuardPhoto?
}

/** [dark]: still nearly black after metering (dark room, covered lens, camera access off). */
class GuardPhoto(val jpeg: ByteArray, val dark: Boolean = false)

/** Guarded-phone side: listens for commands while guarding. */
interface RemoteGuard {
    /** Runs until cancelled; returns at once if this phone is not paired as guarded. */
    suspend fun run(device: GuardDevice)
}

/** What to publish in answer to one command. */
sealed class GuardReply {
    data class Text(val sealed: String) : GuardReply()
    class File(val bytes: ByteArray, val fileName: String, val sealed: String) : GuardReply()
}

/**
 * Turns one ntfy message into at most one reply. Ignores anything that does
 * not decrypt with the pairing key, is a replay, or is too old.
 */
class RemoteGuardHandler(
    private val pairing: RemoteProtocol.Pairing,
    private val encrypt: (ByteArray) -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val key = RemoteProtocol.keyBytes(pairing)
    private val replay = ReplayGuard()
    private var lastPhotoAt = 0L

    suspend fun handle(message: String, device: GuardDevice): GuardReply? {
        val plain = RemoteProtocol.open(message, key, pairing.topic, RemoteProtocol.Direction.COMMAND) ?: return null
        val command = runCatching {
            RemoteProtocol.json.decodeFromString(RemoteProtocol.Command.serializer(), plain)
        }.getOrNull() ?: return null
        val now = clock()
        if (!replay.accept(command.id, command.ts, now)) return null

        return when (command.cmd) {
            "status" -> GuardReply.Text(seal(response(command, "status", status = device.status().toWire())))
            "photo" -> {
                if (now - lastPhotoAt < MIN_PHOTO_INTERVAL_MS) {
                    return GuardReply.Text(seal(response(command, "error", error = "Too many photo requests, wait a few seconds")))
                }
                lastPhotoAt = now
                val photo = device.takePhoto()
                    ?: return GuardReply.Text(seal(response(command, "error", error = "Camera unavailable")))
                GuardReply.File(encrypt(photo.jpeg), "${command.id}.age", seal(response(command, "photo", dark = photo.dark)))
            }
            else -> GuardReply.Text(seal(response(command, "error", error = "Unknown command")))
        }
    }

    private fun response(
        command: RemoteProtocol.Command,
        kind: String,
        status: RemoteProtocol.Status? = null,
        error: String? = null,
        dark: Boolean = false
    ) = RemoteProtocol.Response(
        id = UUID.randomUUID().toString(),
        ts = clock(),
        re = command.id,
        kind = kind,
        status = status,
        error = error,
        dark = dark
    )

    private fun seal(response: RemoteProtocol.Response) = RemoteProtocol.seal(
        RemoteProtocol.json.encodeToString(response), key, pairing.topic, RemoteProtocol.Direction.RESPONSE
    )

    companion object {
        const val MIN_PHOTO_INTERVAL_MS = 5_000L
    }
}

internal fun RemoteStatus.toWire() = RemoteProtocol.Status(
    monitoring = monitoring,
    armed = armed,
    armingRemainingS = armingRemainingSeconds,
    since = guardingSince,
    battery = batteryPercent,
    plugged = pluggedIn,
    lastEventAt = lastEventAt,
    lastEventType = lastEventType
)

internal fun RemoteProtocol.Status.toDomain() = RemoteStatus(
    monitoring = monitoring,
    armed = armed,
    armingRemainingSeconds = armingRemainingS,
    guardingSince = since,
    batteryPercent = battery,
    pluggedIn = plugged,
    lastEventAt = lastEventAt,
    lastEventType = lastEventType
)

@Singleton
class NtfyRemoteGuard @Inject constructor(
    private val config: RemoteConfigStore,
    private val ntfy: NtfyClient,
    private val encryptor: AgeEncryptor
) : RemoteGuard {

    override suspend fun run(device: GuardDevice) {
        if (config.role != RemoteRole.GUARD) return
        val pairing = config.pairing ?: return
        val handler = RemoteGuardHandler(pairing, encryptor::encrypt)
        val commands = RemoteProtocol.commandTopic(pairing.topic)
        val answers = RemoteProtocol.responseTopic(pairing.topic)
        var sinceId: String? = null
        var backoffMs = MIN_BACKOFF_MS

        while (true) {
            try {
                ntfy.subscribe(pairing.server, commands, sinceId).collect { event ->
                    backoffMs = MIN_BACKOFF_MS
                    sinceId = event.id
                    val message = event.message ?: return@collect
                    when (val reply = handler.handle(message, device)) {
                        is GuardReply.Text -> ntfy.publish(pairing.server, answers, reply.sealed)
                        is GuardReply.File -> ntfy.publishFile(pairing.server, answers, reply.bytes, reply.fileName, reply.sealed)
                        null -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("RemoteGuard", "ntfy connection lost: ${e.message}")
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private companion object {
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
