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

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire format of remote control messages, independent of Android so it can
 * be unit tested.
 *
 * Each ntfy message body is "nrp1." + base64url(nonce(12) || AES-256-GCM
 * ciphertext). The key is the 32-byte secret exchanged in the pairing QR
 * code; the additional data binds the message to its channel and direction,
 * so an answer cannot be replayed as a command. ntfy only ever sees opaque
 * text, and photos travel as age files only the controller can decrypt.
 */
object RemoteProtocol {
    const val PREFIX = "nrp1."
    const val PAIRING_PREFIX = "neruppu-pair:v1:"
    /** Commands and answers older or newer than this are ignored. */
    const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L

    enum class Direction(val tag: String) { COMMAND("cmd"), RESPONSE("rsp"), CONFIG("cfg") }

    /** A pairing config this old is no longer applied (ntfy.sh caches 12 h). */
    const val MAX_CONFIG_AGE_MS = 24 * 60 * 60 * 1000L
    /** Above this, a sealed message is sent as an ntfy attachment (body limit 4096 B). */
    const val MAX_INLINE_MESSAGE = 3500

    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    fun commandTopic(base: String) = "$base-c"
    fun responseTopic(base: String) = "$base-r"
    /** Settings the remote hands over at pairing. */
    fun configTopic(base: String) = "$base-p"

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun newTopic(): String = "nrp" + b64.encodeToString(ByteArray(16).also(random::nextBytes))

    fun seal(plaintext: String, key: ByteArray, topic: String, direction: Direction): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(topic, direction))
        return PREFIX + b64.encodeToString(nonce + cipher.doFinal(plaintext.toByteArray()))
    }

    /** Null if the text is not ours, was tampered with, or uses another key or direction. */
    fun open(text: String, key: ByteArray, topic: String, direction: Direction): String? {
        if (!text.startsWith(PREFIX)) return null
        return runCatching {
            val raw = b64d.decode(text.removePrefix(PREFIX))
            require(raw.size > 12 + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
            cipher.updateAAD(aad(topic, direction))
            String(cipher.doFinal(raw, 12, raw.size - 12))
        }.getOrNull()
    }

    private fun aad(topic: String, direction: Direction) = "neruppu-remote-v1:$topic:${direction.tag}".toByteArray()

    fun encodePairing(p: Pairing): String =
        PAIRING_PREFIX + b64.encodeToString(json.encodeToString(p).toByteArray())

    fun decodePairing(code: String): Result<Pairing> = runCatching {
        val trimmed = code.trim()
        require(trimmed.startsWith(PAIRING_PREFIX)) { "Not a Neruppu pairing code" }
        val p = json.decodeFromString<Pairing>(String(b64d.decode(trimmed.removePrefix(PAIRING_PREFIX))))
        require(p.server.startsWith("https://")) { "ntfy server must use https://" }
        require(TOPIC.matches(p.topic)) { "Invalid topic" }
        require(b64d.decode(p.key).size == 32) { "Invalid key" }
        require(p.recipient.startsWith("age1")) { "Invalid age key" }
        p.copy(server = p.server.trimEnd('/'))
    }

    private val TOPIC = Regex("^[A-Za-z0-9_-]{8,60}$")

    fun keyBytes(p: Pairing): ByteArray = b64d.decode(p.key)
    fun keyString(key: ByteArray): String = b64.encodeToString(key)

    @Serializable
    data class Pairing(
        /** ntfy server, e.g. https://ntfy.sh */
        val server: String,
        /** Base topic; commands go to "<topic>-c", answers to "<topic>-r". */
        val topic: String,
        /** base64url of the 32-byte shared key. */
        val key: String,
        /** The controller's age public key; captures get encrypted to it. */
        val recipient: String
    )

    @Serializable
    data class Command(val v: Int = 1, val id: String, val ts: Long, val cmd: String)

    @Serializable
    data class Response(
        val v: Int = 1,
        val id: String,
        val ts: Long,
        /** Id of the command this answers. */
        val re: String,
        /** "status", "photo" or "error". */
        val kind: String,
        val status: Status? = null,
        val error: String? = null
    )

    @Serializable
    data class Status(
        val monitoring: Boolean,
        val armed: Boolean,
        val armingRemainingS: Int,
        val since: Long? = null,
        val battery: Int,
        val plugged: Boolean,
        val lastEventAt: Long? = null,
        val lastEventType: String? = null
    )
}

/** Rejects messages outside the clock window or already seen (ids kept in a bounded set). */
class ReplayGuard(private val capacity: Int = 256) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun accept(id: String, ts: Long, now: Long): Boolean {
        if (kotlin.math.abs(now - ts) > RemoteProtocol.MAX_CLOCK_SKEW_MS) return false
        if (!seen.add(id)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
