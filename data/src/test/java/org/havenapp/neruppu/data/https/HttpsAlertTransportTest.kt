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

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.havenapp.neruppu.data.storage.MediaStorageRepositoryImpl
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.model.MediaFile
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.util.HttpsBodyEncoding
import org.havenapp.neruppu.domain.util.HttpsRequestTemplate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpsAlertTransportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val apiClient = mockk<HttpsApiClient>()
    private val configStore = mockk<HttpsConfigStore>()
    private lateinit var transport: HttpsAlertTransport

    private fun template(encoding: HttpsBodyEncoding, fileField: String) =
        HttpsRequestTemplate.parse("https://example.org", encoding, "", "m={message}", fileField).getOrThrow()

    @Before
    fun setup() {
        transport = HttpsAlertTransport(apiClient, configStore)
        every { configStore.isComplete } returns true
        every { configStore.template() } returns Result.success(template(HttpsBodyEncoding.MULTIPART, "file"))
        coEvery { apiClient.send(any(), any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun blobPayload(): AlertPayload.MediaAlert {
        val file = tempFolder.newFile("neruppu_sound_1000.mp4.age")
        file.writeBytes(byteArrayOf(4, 5, 6))
        return AlertPayload.MediaAlert(
            SensorType.MICROPHONE,
            "Noise burst",
            MediaFile(file.absolutePath, MediaStorageRepositoryImpl.MIME_AGE, 3L, 1000L),
            1000L
        )
    }

    @Test
    fun `TC-HTTPS-10 encrypted blob is attached with opaque message and filename`() = runBlocking {
        val vars = slot<Map<String, String>>()
        val attachment = slot<HttpsApiClient.Attachment>()
        coEvery { apiClient.send(any(), capture(vars), capture(attachment)) } returns Result.success(Unit)

        assertTrue(transport.send(blobPayload()).isSuccess)

        assertFalse(vars.captured["message"]!!.contains("Noise"))
        assertTrue(vars.captured["details"]!!.contains("Noise"))
        assertEquals("file", vars.captured["kind"])
        assertEquals("1", vars.captured["timestamp_s"])
        assertEquals("1000.age", vars.captured["filename"])
        assertEquals("1000.age", attachment.captured.fileName)
        assertArrayEquals(byteArrayOf(4, 5, 6), attachment.captured.bytes)
        assertTrue(transport.deliversMedia)
    }

    @Test
    fun `TC-HTTPS-11 notification-only template sends no file and keeps the capture`() = runBlocking {
        every { configStore.template() } returns Result.success(template(HttpsBodyEncoding.FORM, ""))
        val vars = slot<Map<String, String>>()
        coEvery { apiClient.send(any(), capture(vars), null) } returns Result.success(Unit)

        assertTrue(transport.send(blobPayload()).isSuccess)

        assertEquals("", vars.captured["filename"])
        coVerify { apiClient.send(any(), any(), null) }
        // MediaUploadRepositoryImpl must not delete the only copy.
        assertFalse(transport.deliversMedia)
    }

    @Test
    fun `TC-HTTPS-12 text alert and failure propagation`() = runBlocking {
        val vars = slot<Map<String, String>>()
        coEvery { apiClient.send(any(), capture(vars), null) } returns Result.failure(Exception("Endpoint answered 500"))

        val result = transport.send(AlertPayload.TextAlert(SensorType.LIGHT, "Light change", 1000L))

        assertTrue(result.isFailure)
        assertEquals("text", vars.captured["kind"])
        assertTrue(vars.captured["message"]!!.contains("Light change"))
    }

    @Test
    fun `TC-HTTPS-13 skips dispatch if unconfigured`() = runBlocking {
        every { configStore.template() } returns Result.failure(IllegalArgumentException("no URL"))

        assertTrue(transport.send(AlertPayload.TextAlert(SensorType.LIGHT, "x", 1L)).isSuccess)
        assertTrue(transport.testConnection().isFailure)

        coVerify(exactly = 0) { apiClient.send(any(), any(), any()) }
        assertFalse(transport.deliversMedia)
    }
}
