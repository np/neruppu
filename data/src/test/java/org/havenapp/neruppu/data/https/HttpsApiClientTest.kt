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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.havenapp.neruppu.domain.util.HttpsBodyEncoding
import org.havenapp.neruppu.domain.util.HttpsPreset
import org.havenapp.neruppu.domain.util.HttpsRequestTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class HttpsApiClientTest {
    private val requests = mutableListOf<Pair<HttpRequestData, String>>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = ""): HttpsApiClient {
        val engine = MockEngine { request ->
            requests += request to String(request.body.toByteArray(), Charsets.ISO_8859_1)
            respond(body, status)
        }
        return HttpsApiClient(HttpClient(engine))
    }

    private fun template(
        encoding: HttpsBodyEncoding,
        headers: String = "Authorization: Bearer s3cret",
        fields: String = "kind={kind}\ntimestamp={timestamp_ms}\nmessage={message}",
        fileField: String = "file",
        url: String = "https://example.org/hook"
    ) = HttpsRequestTemplate.parse(url, encoding, headers, fields, fileField).getOrThrow()

    private val vars = HttpsAlertTransport.vars(
        kind = "file", message = "[Neruppu] Alert at x", details = "Motion & more",
        timestamp = 1_760_000_000_123L, fileName = "1760000000123.age"
    )

    @Test
    fun `TC-HTTPS-01 multipart carries rendered fields, headers and raw capture bytes`() = runBlocking {
        val blob = byteArrayOf(0x61, 0x67, 0x65, 0x00, 0xFF.toByte())

        client().send(
            template(HttpsBodyEncoding.MULTIPART), vars,
            HttpsApiClient.Attachment(blob, "1760000000123.age", "application/octet-stream")
        ).getOrThrow()

        val (request, body) = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://example.org/hook", request.url.toString())
        assertEquals("Bearer s3cret", request.headers[HttpHeaders.Authorization])
        assertEquals("Neruppu", request.headers[HttpHeaders.UserAgent])
        assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))
        assertTrue(body.contains("name=timestamp") && body.contains("\r\n\r\n1760000000123\r\n"))
        assertTrue(body.contains("name=file") && body.contains("filename=\"1760000000123.age\""))
        // Bytes are passed through untouched, including NUL and 0xFF.
        assertTrue(body.contains(String(blob, Charsets.ISO_8859_1)))
    }

    @Test
    fun `TC-HTTPS-02 Pushover preset yields a form POST with its parameters`() = runBlocking {
        val p = HttpsPreset.PUSHOVER
        val t = HttpsRequestTemplate.parse(p.url, p.encoding, p.headers, p.fields, p.fileField).getOrThrow()

        // Even if a capture is offered, a template without file field does not send it.
        client().send(t, vars, HttpsApiClient.Attachment(byteArrayOf(1), "x.age", "application/octet-stream")).getOrThrow()

        val (request, body) = requests.single()
        assertEquals("https://api.pushover.net/1/messages.json", request.url.toString())
        assertTrue(request.body.contentType.toString().startsWith("application/x-www-form-urlencoded"))
        val form = body.split('&').associate {
            val (k, v) = it.split('=', limit = 2); k to URLDecoder.decode(v, "UTF-8")
        }
        assertEquals("YOUR_APP_TOKEN", form["token"])
        assertEquals("YOUR_USER_KEY", form["user"])
        assertEquals("[Neruppu] Alert at x", form["message"])
        assertEquals("1760000000", form["timestamp"])
        assertFalse(form.containsKey("file") || form.containsKey("attachment"))
        assertEquals(null, request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `TC-HTTPS-03 JSON encoding escapes values and uses application json`() = runBlocking {
        client().send(
            template(HttpsBodyEncoding.JSON, fields = "topic=alerts\nmessage={details} \"quoted\"", fileField = ""),
            vars
        ).getOrThrow()

        val (request, body) = requests.single()
        assertTrue(request.body.contentType.toString().startsWith("application/json"))
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("alerts", json["topic"]!!.jsonPrimitive.content)
        assertEquals("Motion & more \"quoted\"", json["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `TC-HTTPS-04 non-2xx is a failure that reports the endpoint answer`() = runBlocking {
        val result = client(HttpStatusCode.BadRequest, """{"user":"invalid","errors":["user identifier is invalid"],"status":0}""")
            .send(template(HttpsBodyEncoding.FORM, fileField = ""), vars)

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("400") && message.contains("user identifier is invalid"))
    }

    @Test
    fun `TC-HTTPS-05 redirect is not followed and counts as failure`() = runBlocking {
        val result = client(HttpStatusCode.Found).send(template(HttpsBodyEncoding.FORM, fileField = ""), vars)

        assertFalse(result.isSuccess)
        assertEquals(1, requests.size)
    }

    @Test
    fun `TC-HTTPS-06 a template with a non-https URL is never sent`() = runBlocking {
        val bad = template(HttpsBodyEncoding.FORM, fileField = "").copy(url = "http://example.org/hook")

        assertTrue(client().send(bad, vars).isFailure)
        assertTrue(requests.isEmpty())
    }
}
