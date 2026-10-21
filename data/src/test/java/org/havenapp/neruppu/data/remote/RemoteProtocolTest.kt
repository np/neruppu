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

import org.havenapp.neruppu.data.remote.RemoteProtocol.Direction.COMMAND
import org.havenapp.neruppu.data.remote.RemoteProtocol.Direction.RESPONSE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {

    private val key = RemoteProtocol.newKey()
    private val topic = RemoteProtocol.newTopic()

    @Test
    fun `TC-RC-01 sealed messages open only with the same key, topic and direction`() {
        val sealed = RemoteProtocol.seal("""{"cmd":"photo"}""", key, topic, COMMAND)

        assertTrue(sealed.startsWith(RemoteProtocol.PREFIX))
        assertFalse(sealed.contains("photo"))
        assertEquals("""{"cmd":"photo"}""", RemoteProtocol.open(sealed, key, topic, COMMAND))
        assertNull(RemoteProtocol.open(sealed, RemoteProtocol.newKey(), topic, COMMAND))
        assertNull(RemoteProtocol.open(sealed, key, RemoteProtocol.newTopic(), COMMAND))
        // An answer can never be replayed as a command, nor the reverse.
        assertNull(RemoteProtocol.open(sealed, key, topic, RESPONSE))
    }

    @Test
    fun `TC-RC-02 tampered or foreign messages are rejected`() {
        val sealed = RemoteProtocol.seal("hello", key, topic, COMMAND)
        val flipped = sealed.dropLast(2) + (if (sealed.last() == 'A') "BB" else "AA")

        assertNull(RemoteProtocol.open(flipped, key, topic, COMMAND))
        assertNull(RemoteProtocol.open("plain text from someone else", key, topic, COMMAND))
        assertNull(RemoteProtocol.open("nrp1.!!!", key, topic, COMMAND))
    }

    @Test
    fun `TC-RC-03 pairing code round trip and validation`() {
        val pairing = RemoteProtocol.Pairing(
            server = "https://ntfy.example/",
            topic = topic,
            key = RemoteProtocol.keyString(key),
            recipient = "age1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        )
        val decoded = RemoteProtocol.decodePairing(RemoteProtocol.encodePairing(pairing)).getOrThrow()

        assertEquals("https://ntfy.example", decoded.server)
        assertEquals(topic, decoded.topic)
        assertTrue(RemoteProtocol.keyBytes(decoded).contentEquals(key))

        assertTrue(RemoteProtocol.decodePairing("hello").isFailure)
        assertTrue(RemoteProtocol.decodePairing(RemoteProtocol.encodePairing(pairing.copy(server = "http://ntfy.example"))).isFailure)
        assertTrue(RemoteProtocol.decodePairing(RemoteProtocol.encodePairing(pairing.copy(key = "c2hvcnQ"))).isFailure)
        assertTrue(RemoteProtocol.decodePairing(RemoteProtocol.encodePairing(pairing.copy(topic = "a/b"))).isFailure)
    }

    @Test
    fun `TC-RC-04 replay guard rejects duplicates and stale messages`() {
        val guard = ReplayGuard(capacity = 2)
        val now = 1_000_000_000L

        assertTrue(guard.accept("a", now, now))
        assertFalse(guard.accept("a", now, now))
        assertFalse(guard.accept("old", now - RemoteProtocol.MAX_CLOCK_SKEW_MS - 1, now))
        assertFalse(guard.accept("future", now + RemoteProtocol.MAX_CLOCK_SKEW_MS + 1, now))
        assertTrue(guard.accept("b", now, now))
        assertTrue(guard.accept("c", now, now))
        // Bounded memory: only the latest ids are kept, still enough to catch a resend.
        assertFalse(guard.accept("c", now, now))
    }
}
