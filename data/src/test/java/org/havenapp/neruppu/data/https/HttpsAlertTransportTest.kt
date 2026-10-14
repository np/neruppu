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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Before
    fun setup() {
        transport = HttpsAlertTransport(apiClient, configStore)
        every { configStore.isComplete } returns true
        coEvery { apiClient.post(any(), any(), any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `TC-HTTPS-10 encrypted blob is posted with opaque caption and filename`() = runBlocking {
        val file = tempFolder.newFile("neruppu_sound_1000.mp4.age")
        file.writeBytes(byteArrayOf(4, 5, 6))
        val payload = AlertPayload.MediaAlert(
            SensorType.MICROPHONE,
            "Noise burst",
            MediaFile(file.absolutePath, MediaStorageRepositoryImpl.MIME_AGE, 3L, 1000L),
            1000L
        )
        val message = slot<String>()
        val attachment = slot<HttpsApiClient.Attachment>()
        coEvery {
            apiClient.post(HttpsApiClient.KIND_FILE, capture(message), 1000L, capture(attachment))
        } returns Result.success(Unit)

        assertTrue(transport.send(payload).isSuccess)

        assertFalse(message.captured.contains("Noise"))
        assertEquals("1000.age", attachment.captured.fileName)
        assertEquals("application/octet-stream", attachment.captured.mimeType)
        assertArrayEquals(byteArrayOf(4, 5, 6), attachment.captured.bytes)
    }

    @Test
    fun `TC-HTTPS-11 text alert is posted without attachment`() = runBlocking {
        val payload = AlertPayload.TextAlert(SensorType.LIGHT, "Light change", 1000L)

        assertTrue(transport.send(payload).isSuccess)

        coVerify {
            apiClient.post(HttpsApiClient.KIND_TEXT, match { it.contains("Light change") }, 1000L, null)
        }
    }

    @Test
    fun `TC-HTTPS-12 failure from the endpoint is propagated`() = runBlocking {
        coEvery { apiClient.post(any(), any(), any(), any()) } returns Result.failure(Exception("HTTPS endpoint answered 500"))

        val result = transport.send(AlertPayload.TextAlert(SensorType.LIGHT, "x", 1L))

        assertTrue(result.isFailure)
    }

    @Test
    fun `TC-HTTPS-13 skips dispatch if unconfigured`() = runBlocking {
        every { configStore.isComplete } returns false

        assertTrue(transport.send(AlertPayload.TextAlert(SensorType.LIGHT, "x", 1L)).isSuccess)

        coVerify(exactly = 0) { apiClient.post(any(), any(), any(), any()) }
    }
}
