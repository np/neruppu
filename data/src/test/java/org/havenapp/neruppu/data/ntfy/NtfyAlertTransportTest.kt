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

package org.havenapp.neruppu.data.ntfy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.havenapp.neruppu.data.storage.MediaStorageRepositoryImpl
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.model.MediaFile
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.repository.NtfyAlertConfigRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NtfyAlertTransportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class Config : NtfyAlertConfigRepository {
        override var server = "https://ntfy.sh"
        override var topic = "neruppu-abc123"
        override var accessToken = ""
        override var attachCaptures = true
        override val isComplete get() = NtfyAlertConfigRepository.isValidServer(server) && NtfyAlertConfigRepository.isValidTopic(topic)
        override fun clear() { topic = "" }
    }

    private val config = Config()
    private val requests = mutableListOf<Pair<HttpRequestData, ByteArray>>()

    private fun transport(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): NtfyAlertTransport {
        val engine = MockEngine { request ->
            requests += request to request.body.toByteArray()
            respond(body, status)
        }
        return NtfyAlertTransport(config, HttpClient(engine), linksEnabled = { false })
    }

    private fun blob(): AlertPayload.MediaAlert {
        val file = tempFolder.newFile("neruppu_motion_1000.jpg.age").apply { writeBytes(byteArrayOf(9, 0, 7)) }
        return AlertPayload.MediaAlert(
            SensorType.CAMERA_MOTION, "Motion detected",
            MediaFile(file.absolutePath, MediaStorageRepositoryImpl.MIME_AGE, 3L, 1000L), 1000L
        )
    }

    @Test
    fun `TC-NTFY-01 text alert is a POST of plain text with title and priority headers`() = runBlocking {
        transport().send(AlertPayload.TextAlert(SensorType.LIGHT, "Light change", 1000L)).getOrThrow()

        val (request, body) = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://ntfy.sh/neruppu-abc123", request.url.toString())
        assertEquals("Neruppu", request.headers["Title"])
        assertEquals("high", request.headers["Priority"])
        assertNull(request.headers[HttpHeaders.Authorization])
        assertNull(request.headers["Click"])
        assertTrue(String(body).contains("Light change"))
    }

    @Test
    fun `TC-NTFY-02 capture is a PUT attachment with opaque name and caption`() = runBlocking {
        config.accessToken = "tk_secret"

        val t = transport()
        t.send(blob()).getOrThrow()

        val (request, body) = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("1000.age", request.headers["Filename"])
        assertFalse(request.headers["Message"]!!.contains("Motion"))
        assertEquals("Bearer tk_secret", request.headers[HttpHeaders.Authorization])
        assertArrayEquals(byteArrayOf(9, 0, 7), body)
        assertTrue(t.deliversMedia)
    }

    @Test
    fun `TC-NTFY-03 without attachments only the notification goes and the capture stays`() = runBlocking {
        config.attachCaptures = false

        val t = transport()
        t.send(blob()).getOrThrow()

        val (request, _) = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertNull(request.headers["Filename"])
        assertFalse(t.deliversMedia)
    }

    @Test
    fun `TC-NTFY-04 errors carry ntfy's explanation`() = runBlocking {
        val result = transport(HttpStatusCode.Forbidden, """{"code":40301,"error":"forbidden"}""")
            .send(AlertPayload.TextAlert(SensorType.LIGHT, "x", 1L))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("403") && message.contains("forbidden"))
    }

    @Test
    fun `TC-NTFY-05 topics are random and validated`() {
        val a = NtfyAlertConfigRepository.randomTopic()
        val b = NtfyAlertConfigRepository.randomTopic()
        assertTrue(a.startsWith("neruppu-") && a.length == 28 && a != b)
        assertTrue(NtfyAlertConfigRepository.isValidTopic(a))
        assertFalse(NtfyAlertConfigRepository.isValidTopic("has space"))
        assertFalse(NtfyAlertConfigRepository.isValidTopic("a/b"))
        assertFalse(NtfyAlertConfigRepository.isValidServer("http://ntfy.sh"))
    }
}
