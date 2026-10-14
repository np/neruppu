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

package org.havenapp.neruppu.data.https

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpsApiClientTest {
    private val configStore = mockk<HttpsConfigStore>()
    private val requests = mutableListOf<Pair<HttpRequestData, String>>()

    @Before
    fun setup() {
        every { configStore.endpointUrl } returns "https://example.org/hook"
        every { configStore.authToken } returns "s3cret"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK
    ): HttpsApiClient {
        val engine = MockEngine { request ->
            requests += request to String(request.body.toByteArray(), Charsets.ISO_8859_1)
            respond("", status)
        }
        return HttpsApiClient(configStore, HttpClient(engine))
    }

    @Test
    fun `TC-HTTPS-01 file alert is a multipart POST with bearer token and raw bytes`() = runBlocking {
        val blob = byteArrayOf(0x61, 0x67, 0x65, 0x00, 0xFF.toByte())

        val result = client().post(
            kind = HttpsApiClient.KIND_FILE,
            message = "[Neruppu] Alert at 2026-10-14 06:00:00",
            timestamp = 1000L,
            attachment = HttpsApiClient.Attachment(blob, "1000.age", "application/octet-stream")
        )

        assertTrue(result.isSuccess)
        val (request, body) = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://example.org/hook", request.url.toString())
        assertEquals("Bearer s3cret", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))
        assertTrue(body.contains("name=kind") && body.contains("\r\n\r\nfile\r\n"))
        assertTrue(body.contains("name=timestamp") && body.contains("\r\n\r\n1000\r\n"))
        assertTrue(body.contains("filename=\"1000.age\""))
        assertTrue(body.contains("Content-Type: application/octet-stream"))
        // Bytes are passed through untouched, including NUL and 0xFF.
        assertTrue(body.contains(String(blob, Charsets.ISO_8859_1)))
    }

    @Test
    fun `TC-HTTPS-02 no Authorization header when token is blank`() = runBlocking {
        every { configStore.authToken } returns ""

        client().post(HttpsApiClient.KIND_TEXT, "hello", 1L).getOrThrow()

        assertNull(requests.single().first.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `TC-HTTPS-03 non-2xx response is a failure`() = runBlocking {
        val result = client(HttpStatusCode.Unauthorized).post(HttpsApiClient.KIND_TEST, "t", 1L)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("401"))
    }

    @Test
    fun `TC-HTTPS-04 plain http endpoint is refused before any request`() = runBlocking {
        every { configStore.endpointUrl } returns "http://example.org/hook"

        val result = client().post(HttpsApiClient.KIND_TEXT, "hello", 1L)

        assertTrue(result.isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `TC-HTTPS-05 redirect is not followed and counts as failure`() = runBlocking {
        val result = client(HttpStatusCode.Found).post(HttpsApiClient.KIND_TEXT, "hello", 1L)

        assertFalse(result.isSuccess)
        assertEquals(1, requests.size)
    }
}
