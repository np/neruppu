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

import android.util.Log
import org.havenapp.neruppu.data.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.havenapp.neruppu.data.local.AlertGroupingStore
import org.havenapp.neruppu.data.storage.MediaStorageRepositoryImpl
import org.havenapp.neruppu.data.worker.UploadRunScheduler
import org.havenapp.neruppu.data.worker.MediaUploadWorker
import org.havenapp.neruppu.domain.di.HttpsTransport
import org.havenapp.neruppu.domain.di.MatrixTransport
import org.havenapp.neruppu.domain.di.TelegramTransport
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.MediaFile
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.model.UploadStatus
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.MediaUploadRepository
import org.havenapp.neruppu.domain.repository.SensorRepository
import org.havenapp.neruppu.domain.transport.AlertTransport
import org.havenapp.neruppu.domain.util.AlertGrouping
import org.havenapp.neruppu.domain.util.AlertGroupingConfig
import org.havenapp.neruppu.domain.util.AlertGroupingState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Singleton

@Singleton
// Built by AppModule (needs the UploadRunScheduler lambda), hence no @Inject.
class MediaUploadRepositoryImpl(
    @ApplicationContext private val context: android.content.Context,
    private val sensorRepository: SensorRepository,
    private val alertTargetRepository: AlertTargetRepository,
    @TelegramTransport private val telegramTransport: AlertTransport,
    @MatrixTransport private val matrixTransport: AlertTransport,
    @HttpsTransport private val httpsTransport: AlertTransport,
    private val groupingStore: AlertGroupingStore,
    private val runScheduler: UploadRunScheduler
) : MediaUploadRepository {

    override suspend fun enqueueUpload(eventId: Long) {
        MediaUploadWorker.enqueueUpload(context, eventId)
        if (BuildConfig.DEBUG) {
            Log.d("MediaUploadRepository", "Enqueued upload for event $eventId")
        }
    }

    override suspend fun getPendingUploadEvents(limit: Int): List<Event> = withContext(Dispatchers.IO) {
        sensorRepository.getPendingUploadEvents(limit)
    }

    private val mutex = Mutex()

    /** Wall clock; replaced in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Sends pending events through [AlertGrouping]: the first event of an
     * incident immediately, later ones folded into one summary per window,
     * all under an hourly cap. Grouped captures stay on the device.
     * Runs are serialised: the worker, retries and delayed summary runs may overlap.
     */
    override suspend fun uploadPendingEvents(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { uploadLocked() }
    }

    private suspend fun uploadLocked(): Boolean {
        val target = alertTargetRepository.activeTarget
        val transport = when (target) {
            AlertTarget.TELEGRAM -> telegramTransport
            AlertTarget.MATRIX -> matrixTransport
            AlertTarget.HTTPS -> httpsTransport
            AlertTarget.NONE -> return true
        }
        val config = groupingStore.config
        val now = clock()
        var state = groupingStore.state

        // A summary that became due goes out before newer events, to keep order.
        var (afterFlush, ok) = flushIfDue(state, config, now, transport, target)
        state = afterFlush

        if (ok) {
            for (event in sensorRepository.getPendingUploadEvents(limit = 50)) {
                val capturePath = event.mediaUri ?: event.audioUri
                val (decision, next) = AlertGrouping.onEvent(
                    state, config, event.timestamp.toEpochMilli(), now, event.id, capturePath
                )
                when (decision) {
                    AlertGrouping.Decision.SEND -> when (sendEvent(event, transport, target)) {
                        SendOutcome.SENT -> state = AlertGrouping.recordSend(next, now)
                        // Missing file: marked FAILED, nothing to retry, move on.
                        SendOutcome.PERMANENT_FAILURE -> Unit
                        // Network or endpoint: stop here so order is kept on retry.
                        SendOutcome.TRANSIENT_FAILURE -> { ok = false; break }
                    }
                    AlertGrouping.Decision.GROUP -> {
                        state = next
                        sensorRepository.updateEventUploadStatus(
                            eventId = event.id,
                            status = UploadStatus.GROUPED,
                            target = target.name,
                            uploadedAt = null,
                            failureReason = null
                        )
                    }
                }
            }
        }

        if (ok) {
            val (flushed, flushOk) = flushIfDue(state, config, now, transport, target)
            state = flushed
            ok = flushOk
        }

        groupingStore.state = state
        AlertGrouping.nextFlushAt(state, config, now)?.let { at ->
            if (at != groupingStore.scheduledFlushAt) {
                runScheduler.scheduleRun(at - now)
                groupingStore.scheduledFlushAt = at
            }
        }
        return ok
    }

    private enum class SendOutcome { SENT, TRANSIENT_FAILURE, PERMANENT_FAILURE }

    /** Sends the summary of grouped events if due; returns the new state and success. */
    private suspend fun flushIfDue(
        state: AlertGroupingState,
        config: AlertGroupingConfig,
        now: Long,
        transport: AlertTransport,
        target: AlertTarget
    ): Pair<AlertGroupingState, Boolean> {
        val plan = AlertGrouping.flushPlan(state, config, now) ?: return state to true

        val since = SUMMARY_TIME_FORMAT.format(Instant.ofEpochMilli(plan.since))
        val summary = AlertPayload.TextAlert(
            // Not shown by any transport for text alerts; the summary is deliberately
            // generic: a count, no sensor types.
            sensorType = SensorType.POWER,
            message = "+${plan.count} more event${if (plan.count > 1) "s" else ""} since $since",
            timestamp = now
        )
        if (transport.send(summary).isFailure) return state to false

        // One capture per window, the first grouped one; the others stay on the device.
        val path = plan.capturePath
        if (path != null && File(path).exists()) {
            val file = File(path)
            val capture = AlertPayload.MediaAlert(
                sensorType = SensorType.POWER,
                message = "Capture",
                mediaFile = MediaFile(path, guessMimeType(path), file.length(), now),
                timestamp = now
            )
            if (transport.send(capture).isSuccess) {
                if (transport.deliversMedia) deleteMediaFile(path)
                sensorRepository.updateEventUploadStatus(
                    eventId = plan.captureEventId,
                    status = UploadStatus.UPLOADED,
                    target = target.name,
                    uploadedAt = now,
                    failureReason = null
                )
            } else if (BuildConfig.DEBUG) {
                Log.w("MediaUploadRepository", "Summary capture not sent; kept on device")
            }
        }
        return AlertGrouping.afterFlush(state, now) to true
    }

    /** Sends one event (image, then audio, or text if it has neither). */
    private suspend fun sendEvent(event: Event, transport: AlertTransport, target: AlertTarget): SendOutcome {
        val media = listOfNotNull(
            event.mediaUri?.let { it to "Image" },
            event.audioUri?.let { it to "Audio" }
        )
        for ((path, label) in media) {
            val file = File(path)
            if (!file.exists()) {
                sensorRepository.updateEventUploadStatus(
                    eventId = event.id,
                    status = UploadStatus.FAILED,
                    target = target.name,
                    uploadedAt = null,
                    failureReason = "$label file not found: $path"
                )
                return SendOutcome.PERMANENT_FAILURE
            }
            val payload = AlertPayload.MediaAlert(
                sensorType = event.sensorType,
                message = event.description,
                mediaFile = MediaFile(
                    absolutePath = path,
                    mimeType = guessMimeType(path),
                    sizeBytes = file.length(),
                    timestamp = event.timestamp.toEpochMilli()
                ),
                timestamp = event.timestamp.toEpochMilli()
            )
            val result = transport.send(payload)
            if (result.isFailure) {
                sensorRepository.updateEventUploadStatus(
                    eventId = event.id,
                    status = UploadStatus.FAILED,
                    target = target.name,
                    uploadedAt = null,
                    failureReason = "$label upload failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                )
                return SendOutcome.TRANSIENT_FAILURE
            }
            if (transport.deliversMedia) deleteMediaFile(path)
        }

        if (media.isEmpty()) {
            val result = transport.send(
                AlertPayload.TextAlert(
                    sensorType = event.sensorType,
                    message = event.description,
                    timestamp = event.timestamp.toEpochMilli()
                )
            )
            if (result.isFailure) {
                sensorRepository.updateEventUploadStatus(
                    eventId = event.id,
                    status = UploadStatus.FAILED,
                    target = target.name,
                    uploadedAt = null,
                    failureReason = "Alert failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                )
                return SendOutcome.TRANSIENT_FAILURE
            }
        }

        sensorRepository.updateEventUploadStatus(
            eventId = event.id,
            status = UploadStatus.UPLOADED,
            target = target.name,
            uploadedAt = System.currentTimeMillis(),
            failureReason = null
        )
        return SendOutcome.SENT
    }

    private fun guessMimeType(path: String): String {
        return when {
            // First: neruppu_motion_123.jpg.age must not match the .jpg arm.
            path.endsWith(".age", ignoreCase = true) -> MediaStorageRepositoryImpl.MIME_AGE
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".mp4", ignoreCase = true) -> "audio/mp4"
            path.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    private fun deleteMediaFile(path: String) {
        val file = File(path)
        if (!file.exists()) {
            if (BuildConfig.DEBUG) {
                Log.d("MediaUploadRepository", "Media file already missing: $path")
            }
            return
        }

        try {
            if (file.delete()) {
                if (BuildConfig.DEBUG) {
                    Log.d("MediaUploadRepository", "Deleted media file: $path")
                }
            } else {
                if (BuildConfig.DEBUG) {
                    Log.w("MediaUploadRepository", "Failed to delete media file: $path")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w("MediaUploadRepository", "Failed to delete media file: $path", e)
            }
        }
    }

    private companion object {
        val SUMMARY_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
