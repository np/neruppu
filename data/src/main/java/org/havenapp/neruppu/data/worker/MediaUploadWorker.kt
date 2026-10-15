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

package org.havenapp.neruppu.data.worker

import android.content.Context
import android.util.Log
import org.havenapp.neruppu.data.BuildConfig
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.havenapp.neruppu.domain.repository.MediaUploadRepository
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaUploadRepositoryEntryPoint {
    fun mediaUploadRepository(): MediaUploadRepository
}

class MediaUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            MediaUploadRepositoryEntryPoint::class.java
        ).mediaUploadRepository()

        return try {
            if (repository.uploadPendingEvents()) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w("MediaUploadWorker", "Upload worker failed; retrying", e)
            }
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "media_upload"
        private const val KEY_EVENT_ID = "event_id"

        fun enqueueUpload(context: Context, eventId: Long) {
            val data = androidx.work.Data.Builder()
                .putLong(KEY_EVENT_ID, eventId)
                .build()

            val request = OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            // KEEP: one run uploads every pending event, so a run that is
            // waiting (or retrying) already covers this one. APPEND would chain
            // new events behind a retrying run and hold them until it succeeds.
            // While guarding, MonitoringService delivers directly; this is the
            // fallback.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Runs the upload again after [delayMs], to send a pending incident
         * summary even if no new event arrives. Not unique: the repository
         * serialises runs and an extra run is harmless.
         */
        fun scheduleRun(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(FLUSH_TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        private const val FLUSH_TAG = "media_upload_flush"

        /**
         * Linear 1, 2, 3... min. The former exponential backoff from 15 min
         * reached hours after a few failures (15+30+60+120+240 min), holding
         * every later alert back for most of a day.
         */
        private const val RETRY_BACKOFF_SECONDS = 60L
    }
}