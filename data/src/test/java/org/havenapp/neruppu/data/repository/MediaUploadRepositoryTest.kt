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

package org.havenapp.neruppu.data.repository

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.model.UploadStatus
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.MediaUploadRepository
import org.havenapp.neruppu.domain.repository.SensorRepository
import org.havenapp.neruppu.data.local.AlertGroupingStore
import org.havenapp.neruppu.data.worker.UploadRunScheduler
import org.havenapp.neruppu.domain.transport.AlertTransport
import org.havenapp.neruppu.domain.util.AlertGroupingConfig
import org.havenapp.neruppu.domain.util.AlertGroupingState
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaUploadRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sensorRepository: SensorRepository
    private lateinit var alertTargetRepository: AlertTargetRepository
    private lateinit var telegramTransport: AlertTransport
    private lateinit var matrixTransport: AlertTransport
    private lateinit var httpsTransport: AlertTransport
    private lateinit var mediaUploadRepository: MediaUploadRepository
    private lateinit var groupingStore: FakeGroupingStore
    private val scheduledDelays = mutableListOf<Long>()

    private lateinit var testFile: File

    @Before
    fun setup() {
        sensorRepository = mockk()
        alertTargetRepository = mockk(relaxed = true)
        telegramTransport = mockk()
        matrixTransport = mockk()
        httpsTransport = mockk()
        listOf(telegramTransport, matrixTransport, httpsTransport).forEach {
            every { it.deliversMedia } returns true
        }
        mediaUploadRepository = MediaUploadRepositoryImpl(
            context = mockk(),
            sensorRepository = sensorRepository,
            alertTargetRepository = alertTargetRepository,
            telegramTransport = telegramTransport,
            matrixTransport = matrixTransport,
            httpsTransport = httpsTransport,
            ntfyTransport = mockk(relaxed = true),
            groupingStore = FakeGroupingStore().also { groupingStore = it },
            runScheduler = UploadRunScheduler { scheduledDelays += it }
        )
        testFile = tempFolder.newFile("test.jpg")
        testFile.writeBytes(byteArrayOf(1, 2, 3))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `TC-MUP-01 No active target does not upload`() = runBlocking {
        every { alertTargetRepository.activeTarget } returns AlertTarget.NONE
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns emptyList()

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        coVerify(exactly = 0) { telegramTransport.send(any()) }
        coVerify(exactly = 0) { matrixTransport.send(any()) }
    }

    @Test
    fun `TC-MUP-02 Telegram success updates status and deletes file`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.CAMERA_MOTION,
            description = "Motion detected",
            mediaUri = testFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        val payloadSlot = slot<AlertPayload>()
        coEvery { telegramTransport.send(capture(payloadSlot)) } returns Result.success(Unit)

        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.UPLOADED,
            target = "TELEGRAM",
            uploadedAt = any(),
            failureReason = null
        ) }
        assertEquals(testFile.absolutePath, (payloadSlot.captured as AlertPayload.MediaAlert).mediaFile.absolutePath)
        assertFalse(testFile.exists())
    }

    @Test
    fun `TC-MUP-03 Telegram failure keeps file and marks failed`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.CAMERA_MOTION,
            description = "Motion detected",
            mediaUri = testFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { telegramTransport.send(any()) } returns Result.failure(RuntimeException("Network error"))

        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertFalse(result)
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.FAILED,
            target = "TELEGRAM",
            uploadedAt = null,
            failureReason = "Image upload failed: Network error"
        ) }
        assertTrue(testFile.exists())
    }

    @Test
    fun `TC-MUP-04 Matrix success uploads via matrix transport`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.MICROPHONE,
            description = "Sound detected",
            audioUri = testFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.MATRIX
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { matrixTransport.send(any()) } returns Result.success(Unit)

        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        coVerify(exactly = 0) { telegramTransport.send(any()) }
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.UPLOADED,
            target = "MATRIX",
            uploadedAt = any(),
            failureReason = null
        ) }
        assertFalse(testFile.exists())
    }

    @Test
    fun `TC-MUP-07 HTTPS target uploads via https transport`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.CAMERA_MOTION,
            description = "Motion",
            mediaUri = testFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.HTTPS
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { httpsTransport.send(any()) } returns Result.success(Unit)
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        assertTrue(mediaUploadRepository.uploadPendingEvents())

        coVerify(exactly = 1) { httpsTransport.send(any()) }
        coVerify(exactly = 0) { telegramTransport.send(any()) }
        coVerify(exactly = 0) { matrixTransport.send(any()) }
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.UPLOADED,
            target = "HTTPS",
            uploadedAt = any(),
            failureReason = null
        ) }
    }

    @Test
    fun `TC-MUP-08 Capture is kept when the transport does not deliver media`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.CAMERA_MOTION,
            description = "Motion",
            mediaUri = testFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.HTTPS
        every { httpsTransport.deliversMedia } returns false
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { httpsTransport.send(any()) } returns Result.success(Unit)
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        assertTrue(mediaUploadRepository.uploadPendingEvents())

        assertTrue(testFile.exists())
        coVerify { sensorRepository.updateEventUploadStatus(1L, UploadStatus.UPLOADED, "HTTPS", any(), null) }
    }

    @Test
    fun `TC-MUP-09 A burst sends the first event and groups the rest, keeping their captures`() = runBlocking {
        val t0 = 1_760_000_000_000L
        val files = (0..2).map { i -> tempFolder.newFile("c$i.jpg.age").apply { writeBytes(byteArrayOf(i.toByte())) } }
        val events = files.mapIndexed { i, f ->
            Event(
                id = i + 1L,
                timestamp = Instant.ofEpochMilli(t0 + i * 3_000L),
                sensorType = SensorType.CAMERA_MOTION,
                description = "Motion $i",
                mediaUri = f.absolutePath,
                uploadStatus = UploadStatus.PENDING
            )
        }
        (mediaUploadRepository as MediaUploadRepositoryImpl).clock = { t0 + 10_000 }
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns events
        coEvery { telegramTransport.send(any()) } returns Result.success(Unit)
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        assertTrue(mediaUploadRepository.uploadPendingEvents())

        coVerify(exactly = 1) { telegramTransport.send(any()) }
        assertFalse(files[0].exists())
        assertTrue(files[1].exists() && files[2].exists())
        coVerify { sensorRepository.updateEventUploadStatus(2L, UploadStatus.GROUPED, "TELEGRAM", null, null) }
        coVerify { sensorRepository.updateEventUploadStatus(3L, UploadStatus.GROUPED, "TELEGRAM", null, null) }
        assertEquals(2, groupingStore.state.groupedCount)
        // Summary run scheduled for the end of the 2-minute window opened by the first event.
        assertEquals(listOf(2 * 60_000L - 10_000), scheduledDelays)
    }

    @Test
    fun `TC-MUP-10 Due summary sends a count and one capture`() = runBlocking {
        val t0 = 1_760_000_000_000L
        val capture = tempFolder.newFile("c1.jpg.age").apply { writeBytes(byteArrayOf(9)) }
        groupingStore.state = AlertGroupingState(
            incidentLastEventAt = t0 + 6_000,
            windowStart = t0,
            groupedCount = 2,
            firstGroupedAt = t0 + 3_000,
            captureEventId = 2L,
            capturePath = capture.absolutePath,
            sentAt = listOf(t0)
        )
        (mediaUploadRepository as MediaUploadRepositoryImpl).clock = { t0 + 2 * 60_000L }
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns emptyList()
        val sent = mutableListOf<AlertPayload>()
        coEvery { telegramTransport.send(capture(sent)) } returns Result.success(Unit)
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        assertTrue(mediaUploadRepository.uploadPendingEvents())

        assertEquals(2, sent.size)
        val summary = sent[0] as AlertPayload.TextAlert
        assertTrue(summary.message.startsWith("+2 more events since"))
        assertEquals(capture.absolutePath, (sent[1] as AlertPayload.MediaAlert).mediaFile.absolutePath)
        assertFalse(capture.exists())
        coVerify { sensorRepository.updateEventUploadStatus(2L, UploadStatus.UPLOADED, "TELEGRAM", any(), null) }
        assertEquals(0, groupingStore.state.groupedCount)
        assertEquals(2, groupingStore.state.sentAt.size)
    }

    @Test
    fun `TC-MUP-11 A failed send stops the run so the order is kept for the retry`() = runBlocking {
        val events = (1L..2L).map { id ->
            Event(id = id, sensorType = SensorType.LIGHT, description = "Light $id", uploadStatus = UploadStatus.PENDING)
        }
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns events
        coEvery { telegramTransport.send(any()) } returns Result.failure(Exception("offline"))
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        assertFalse(mediaUploadRepository.uploadPendingEvents())

        coVerify(exactly = 1) { telegramTransport.send(any()) }
        // Nothing recorded: the next run starts the incident again with event 1.
        assertEquals(AlertGroupingState(), groupingStore.state)
        coVerify(exactly = 0) { sensorRepository.updateEventUploadStatus(2L, any(), any(), any(), any()) }
    }

    @Test
    fun `TC-MUP-05 Text event without media is sent and marked uploaded`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.ACCELEROMETER,
            description = "Motion detected",
            mediaUri = null,
            audioUri = null,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { telegramTransport.send(any()) } returns Result.success(Unit)

        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.UPLOADED,
            target = "TELEGRAM",
            uploadedAt = any(),
            failureReason = null
        ) }
    }

    @Test
    fun `TC-MUP-06 Event with both image and audio uploads both media files`() = runBlocking {
        val imageFile = File(tempFolder.root, "neruppu_motion_123.jpg").apply { createNewFile() }
        imageFile.writeBytes(byteArrayOf(1, 2, 3))
        val audioFile = File(tempFolder.root, "neruppu_sound_123.mp4").apply { createNewFile() }
        audioFile.writeBytes(byteArrayOf(4, 5, 6))

        val event = Event(
            id = 1L,
            sensorType = SensorType.MICROPHONE,
            description = "Sound detected",
            mediaUri = imageFile.absolutePath,
            audioUri = audioFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { telegramTransport.send(any()) } returns Result.success(Unit)
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        // Verify both files were deleted after successful upload
        coVerify(exactly = 2) { telegramTransport.send(any()) }
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.UPLOADED,
            target = "TELEGRAM",
            uploadedAt = any(),
            failureReason = null
        ) }
        assertFalse(imageFile.exists())
        assertFalse(audioFile.exists())
    }
}

private class FakeGroupingStore : AlertGroupingStore {
    override var state = AlertGroupingState()
    override val config = AlertGroupingConfig(windowMs = 2 * 60_000L, quietMs = 5 * 60_000L, hourlyCap = 30)
    override var scheduledFlushAt = 0L
}
