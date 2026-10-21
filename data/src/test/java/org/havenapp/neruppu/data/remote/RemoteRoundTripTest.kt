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

    private object NoSettings : DeviceConfigSource {
        override fun collect(now: Long) = DeviceConfig(ts = now)
        override fun apply(config: DeviceConfig) = Unit
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
        val remote = RemoteControlRepositoryImpl(remoteConfig, ntfy, keyring, MemoryKeys(), NoSettings)
        val code = remote.createControllerPairing("https://ntfy.test")
        assertEquals(RemoteRole.CONTROLLER, remote.role)

        val guardConfig = MemoryConfig()
        val guardKeys = MemoryKeys()
        val guardSide = RemoteControlRepositoryImpl(guardConfig, ntfy, keyring, guardKeys, NoSettings)
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
    fun `TC-RC-11 a remote phone refuses to become a guarded phone`() = runBlocking {
        val config = MemoryConfig()
        val repo = RemoteControlRepositoryImpl(config, ntfy, keyring, MemoryKeys(), NoSettings)
        val code = repo.createControllerPairing("https://ntfy.test")

        val result = repo.pairAsGuard(code)

        assertTrue(result.isFailure)
        assertEquals(RemoteRole.CONTROLLER, config.role)
        Unit
    }

    @Test
    fun `TC-RC-12 guard ignores replays, foreign text, and rate-limits photos`() = runBlocking {
        val remoteConfig = MemoryConfig()
        val remote = RemoteControlRepositoryImpl(remoteConfig, ntfy, keyring, MemoryKeys(), NoSettings)
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

    // --- Settings handed over at pairing ---------------------------------

    private class MemoryPrefs : android.content.SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            override fun putString(k: String?, v: String?) = apply { pending[k!!] = v }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { pending[k!!] = v }
            override fun putInt(k: String?, v: Int) = apply { pending[k!!] = v }
            override fun putLong(k: String?, v: Long) = apply { pending[k!!] = v }
            override fun putFloat(k: String?, v: Float) = apply { pending[k!!] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { pending[k!!] = v }
            override fun remove(k: String?) = apply { pending[k!!] = null }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean { pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; return true }
            override fun apply() { commit() }
        }
    }

    private class Target : org.havenapp.neruppu.domain.repository.AlertTargetRepository {
        private var current = org.havenapp.neruppu.domain.model.AlertTarget.NONE
        override val activeTarget get() = current
        override fun setActiveTarget(target: org.havenapp.neruppu.domain.model.AlertTarget) { current = target }
        override fun clearActiveTarget() { current = org.havenapp.neruppu.domain.model.AlertTarget.NONE }
    }

    private class Telegram : org.havenapp.neruppu.domain.repository.TelegramConfigRepository {
        override var botToken = ""
        override var chatId = ""
        override val isComplete get() = botToken.isNotBlank() && chatId.isNotBlank()
        override fun clear() { botToken = ""; chatId = "" }
    }

    private class Matrix : org.havenapp.neruppu.domain.repository.MatrixConfigRepository {
        override var homeserverUrl = ""
        override var roomId = ""
        override var accessToken = ""
        override val isComplete get() = homeserverUrl.isNotBlank() && roomId.isNotBlank() && accessToken.isNotBlank()
        override fun clear() { homeserverUrl = ""; roomId = ""; accessToken = "" }
    }

    private class Https : org.havenapp.neruppu.domain.repository.HttpsConfigRepository {
        override var endpointUrl = ""
        override var bodyEncoding = org.havenapp.neruppu.domain.util.HttpsBodyEncoding.MULTIPART
        override var headers = ""
        override var fields = ""
        override var fileField = ""
        override val isComplete get() = endpointUrl.startsWith("https://") && fields.isNotBlank()
        override fun clear() { endpointUrl = ""; headers = ""; fields = ""; fileField = "" }
    }

    private class Phone {
        val prefs = MemoryPrefs()
        val target = Target()
        val telegram = Telegram()
        val matrix = Matrix()
        val https = Https()
        val keys = MemoryKeys()
        val config = MemoryConfig()
        val settings = DeviceConfigTransfer({ prefs }, telegram, matrix, https, target, keys, { it.startsWith("age1") })
    }

    private fun repo(phone: Phone) = RemoteControlRepositoryImpl(phone.config, ntfy, keyring, phone.keys, phone.settings)

    @Test
    fun `TC-RC-13 pairing hands over sensors, delays, alert channel and age keys`() = runBlocking {
        val remotePhone = Phone().apply {
            prefs.edit().putBoolean("light_enabled", true).putFloat("motion_sensitivity", 0.8f)
                .putInt("arming_delay_seconds", 300).putInt("alert_hourly_cap", 10)
                .putString("session_start_time", "not a setting").commit()
            telegram.botToken = "123:ABC"; telegram.chatId = "42"
            target.setActiveTarget(org.havenapp.neruppu.domain.model.AlertTarget.TELEGRAM)
            keys.recipients = "age1desktopkey"
        }
        val guardPhone = Phone().apply {
            matrix.homeserverUrl = "https://old"; matrix.roomId = "!r"; matrix.accessToken = "t"
            target.setActiveTarget(org.havenapp.neruppu.domain.model.AlertTarget.MATRIX)
            keys.recipients = ""
        }

        val remote = repo(remotePhone)
        val code = remote.createControllerPairing("https://ntfy.test")
        remote.publishSettings().getOrThrow()
        val published = ntfyServer.topics[RemoteProtocol.configTopic(remotePhone.config.pairing!!.topic)]!!.single()
        assertFalse("123:ABC" in published.message)

        assertEquals(true, repo(guardPhone).pairAsGuard(code).getOrThrow())

        with(guardPhone) {
            assertEquals(true, prefs.getBoolean("light_enabled", false))
            assertEquals(0.8f, prefs.getFloat("motion_sensitivity", 0f))
            assertEquals(300, prefs.getInt("arming_delay_seconds", 0))
            assertEquals(10, prefs.getInt("alert_hourly_cap", 0))
            assertFalse(prefs.contains("session_start_time"))
            assertEquals(org.havenapp.neruppu.domain.model.AlertTarget.TELEGRAM, target.activeTarget)
            assertEquals("123:ABC", telegram.botToken)
            assertEquals("", matrix.accessToken)
            // Remote's pairing key and its other keys, so both can open captures.
            assertEquals(listOf("age1testrecipient", "age1desktopkey"), keys.recipients.lines())
            assertEquals(RemoteRole.GUARD, config.role)
        }
    }

    @Test
    fun `TC-RC-14 a long HTTPS template travels as an attachment`() = runBlocking {
        val remotePhone = Phone().apply {
            https.endpointUrl = "https://api.example/hook"
            https.bodyEncoding = org.havenapp.neruppu.domain.util.HttpsBodyEncoding.JSON
            https.fields = (1..300).joinToString("\n") { "field$it={message}" }
            target.setActiveTarget(org.havenapp.neruppu.domain.model.AlertTarget.HTTPS)
        }
        val guardPhone = Phone()

        val remote = repo(remotePhone)
        val code = remote.createControllerPairing("https://ntfy.test")
        remote.publishSettings().getOrThrow()
        assertTrue(ntfyServer.topics.values.flatten().single().file != null)

        assertEquals(true, repo(guardPhone).pairAsGuard(code).getOrThrow())
        assertEquals(remotePhone.https.fields, guardPhone.https.fields)
        assertEquals(org.havenapp.neruppu.domain.util.HttpsBodyEncoding.JSON, guardPhone.https.bodyEncoding)
        assertEquals(org.havenapp.neruppu.domain.model.AlertTarget.HTTPS, guardPhone.target.activeTarget)
    }

    @Test
    fun `TC-RC-15 without published settings pairing still works and says so`() = runBlocking {
        val remote = repo(Phone())
        val code = remote.createControllerPairing("https://ntfy.test")
        val guardPhone = Phone()

        assertEquals(false, repo(guardPhone).pairAsGuard(code).getOrThrow())
        assertEquals(RemoteRole.GUARD, guardPhone.config.role)
    }
}
