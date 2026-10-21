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

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.havenapp.neruppu.domain.remote.RemoteCommand
import org.havenapp.neruppu.domain.remote.RemoteRole
import org.havenapp.neruppu.domain.remote.RemoteStatus
import org.havenapp.neruppu.domain.remote.RemoteUpdate
import org.havenapp.neruppu.domain.repository.EncryptionKeyRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remote phone and guarded phone talking through an in-memory ntfy server
 * (Ktor MockEngine), with a fake age keyring: "encryption" XORs with the
 * identity length, enough to prove the photo goes out encrypted and only the
 * controller turns it back into the JPEG.
 */
class RemoteRoundTripTest {

    /** In-memory ntfy: topic -> published (message, attachment bytes?) */
    private class FakeNtfy {
        data class Published(val id: String, val message: String, val file: ByteArray?)
        val topics = mutableMapOf<String, MutableList<Published>>()
        var counter = 0

        val engine = MockEngine { request ->
            val path = request.url.encodedPath.trimStart('/')
            when {
                request.method == HttpMethod.Get && path.endsWith("/json") -> {
                    val topic = path.removeSuffix("/json")
                    val lines = buildString {
                        append("""{"id":"ka","time":1,"event":"keepalive"}""").append('\n')
                        topics[topic].orEmpty().forEach { p ->
                            val attachment = if (p.file != null) ""","attachment":{"name":"x.age","url":"https://ntfy.test/file/${p.id}"}""" else ""
                            append("""{"id":"${p.id}","time":1,"event":"message","message":"${p.message}"$attachment}""").append('\n')
                        }
                    }
                    respond(lines, HttpStatusCode.OK, headersOf("Content-Type", "application/x-ndjson"))
                }
                request.method == HttpMethod.Get && path.startsWith("file/") -> {
                    val id = path.removePrefix("file/")
                    respond(topics.values.flatten().first { it.id == id }.file!!, HttpStatusCode.OK)
                }
                request.method == HttpMethod.Post -> {
                    topics.getOrPut(path) { mutableListOf() } += Published("m${counter++}", String(request.body.toByteArray()), null)
                    respond("{}", HttpStatusCode.OK)
                }
                request.method == HttpMethod.Put -> {
                    topics.getOrPut(path) { mutableListOf() } +=
                        Published("m${counter++}", request.headers["Message"]!!, request.body.toByteArray())
                    respond("{}", HttpStatusCode.OK)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private class FakeKeyring : AgeKeyring {
        override fun generateIdentity() = "AGE-SECRET-KEY-1TEST"
        override fun recipientOf(identity: String) = "age1testrecipient"
        override fun decrypt(ciphertext: ByteArray, identity: String) = xor(ciphertext, identity.length)
        override fun isValidRecipient(recipient: String) = recipient.startsWith("age1")
        companion object { fun xor(b: ByteArray, k: Int) = ByteArray(b.size) { (b[it].toInt() xor k).toByte() } }
    }

    private class MemoryConfig : RemoteConfigStore {
        override var role = RemoteRole.NONE
        override var pairing: RemoteProtocol.Pairing? = null
        override var identity: String? = null
        override fun saveController(pairing: RemoteProtocol.Pairing, identity: String) {
            role = RemoteRole.CONTROLLER; this.pairing = pairing; this.identity = identity
        }
        override fun saveGuard(pairing: RemoteProtocol.Pairing) { role = RemoteRole.GUARD; this.pairing = pairing; identity = null }
        override fun clear() { role = RemoteRole.NONE; pairing = null; identity = null }
    }

    private class MemoryKeys : EncryptionKeyRepository {
        override var recipients = "age1existingkey"
        override val isConfigured get() = recipients.isNotBlank()
        override fun setRecipients(keys: List<String>): Boolean { recipients = keys.joinToString("\n"); return true }
        override fun clear() { recipients = "" }
    }

    private val ntfyServer = FakeNtfy()
    private val ntfy = NtfyClient(HttpClient(ntfyServer.engine) { install(HttpTimeout) })
    private val keyring = FakeKeyring()

    private val status = RemoteStatus(
        monitoring = true, armed = true, armingRemainingSeconds = 0, guardingSince = 1L,
        batteryPercent = 64, pluggedIn = false, lastEventAt = null, lastEventType = null
    )
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    private val device = object : GuardDevice {
        override suspend fun status() = status
        override suspend fun takePhoto() = jpeg
    }

    @Test
    fun `TC-RC-10 status and photo travel encrypted and come back readable`() = runBlocking {
        // Remote phone creates the pairing; guarded phone scans it.
        val remoteConfig = MemoryConfig()
        val remote = RemoteControlRepositoryImpl(remoteConfig, ntfy, keyring, MemoryKeys())
        val code = remote.createControllerPairing("https://ntfy.test")
        assertEquals(RemoteRole.CONTROLLER, remote.role)

        val guardConfig = MemoryConfig()
        val guardKeys = MemoryKeys()
        val guardSide = RemoteControlRepositoryImpl(guardConfig, ntfy, keyring, guardKeys)
        guardSide.pairAsGuard(code).getOrThrow()
        assertEquals(RemoteRole.GUARD, guardConfig.role)
        assertNull(guardConfig.identity)
        assertEquals("age1existingkey\nage1testrecipient", guardKeys.recipients)

        // Remote sends two commands.
        remote.send(RemoteCommand.STATUS).getOrThrow()
        remote.send(RemoteCommand.PHOTO).getOrThrow()
        val pairing = guardConfig.pairing!!
        val commands = ntfyServer.topics[RemoteProtocol.commandTopic(pairing.topic)]!!
        assertEquals(2, commands.size)
        assertFalse(commands.any { "photo" in it.message || "status" in it.message })

        // Guarded phone handles them (as NtfyRemoteGuard does) and publishes answers.
        val handler = RemoteGuardHandler(pairing, encrypt = { FakeKeyring.xor(it, keyring.generateIdentity().length) })
        for (c in commands) {
            when (val reply = handler.handle(c.message, device)) {
                is GuardReply.Text -> ntfy.publish(pairing.server, RemoteProtocol.responseTopic(pairing.topic), reply.sealed)
                is GuardReply.File -> ntfy.publishFile(pairing.server, RemoteProtocol.responseTopic(pairing.topic), reply.bytes, reply.fileName, reply.sealed)
                null -> error("command ignored")
            }
        }
        val photoOnTheWire = ntfyServer.topics[RemoteProtocol.responseTopic(pairing.topic)]!!.single { it.file != null }.file!!
        assertFalse(photoOnTheWire.contentEquals(jpeg))

        // Remote receives both answers.
        val updates = remote.updates().filter { it !is RemoteUpdate.Connected }
        val received = mutableListOf<RemoteUpdate>()
        updates.first { received += it; received.size == 2 }
        assertEquals(status, (received[0] as RemoteUpdate.Status).status)
        assertArrayEquals(jpeg, (received[1] as RemoteUpdate.Photo).jpeg)
    }

    @Test
    fun `TC-RC-11 a remote phone refuses to become a guarded phone`() {
        val config = MemoryConfig()
        val repo = RemoteControlRepositoryImpl(config, ntfy, keyring, MemoryKeys())
        val code = repo.createControllerPairing("https://ntfy.test")

        val result = repo.pairAsGuard(code)

        assertTrue(result.isFailure)
        assertEquals(RemoteRole.CONTROLLER, config.role)
    }

    @Test
    fun `TC-RC-12 guard ignores replays, foreign text, and rate-limits photos`() = runBlocking {
        val remoteConfig = MemoryConfig()
        val remote = RemoteControlRepositoryImpl(remoteConfig, ntfy, keyring, MemoryKeys())
        remote.createControllerPairing("https://ntfy.test")
        val pairing = remoteConfig.pairing!!
        var now = 1_000_000L
        remote.clock = { now }
        val handler = RemoteGuardHandler(pairing, encrypt = { it }, clock = { now })

        remote.send(RemoteCommand.PHOTO).getOrThrow()
        remote.send(RemoteCommand.PHOTO).getOrThrow()
        val (first, second) = ntfyServer.topics[RemoteProtocol.commandTopic(pairing.topic)]!!

        assertTrue(handler.handle(first.message, device) is GuardReply.File)
        // Same message again: replay, ignored.
        assertNull(handler.handle(first.message, device))
        // Second photo right away: refused with an error answer.
        val limited = handler.handle(second.message, device) as GuardReply.Text
        val opened = RemoteProtocol.open(limited.sealed, RemoteProtocol.keyBytes(pairing), pairing.topic, RemoteProtocol.Direction.RESPONSE)!!
        assertTrue(opened.contains("\"kind\":\"error\""))
        assertNull(handler.handle("hello from a stranger", device))
        // A command older than the clock window is ignored.
        remote.send(RemoteCommand.STATUS).getOrThrow()
        now += RemoteProtocol.MAX_CLOCK_SKEW_MS + 1
        assertNull(handler.handle(ntfyServer.topics[RemoteProtocol.commandTopic(pairing.topic)]!!.last().message, device))
    }
}
