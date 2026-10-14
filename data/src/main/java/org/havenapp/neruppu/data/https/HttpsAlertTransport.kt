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

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.havenapp.neruppu.data.BuildConfig
import org.havenapp.neruppu.data.storage.MediaStorageRepositoryImpl
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.transport.AlertTransport
import org.havenapp.neruppu.domain.util.AlertMessageFormatter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends alerts to a generic HTTPS endpoint: one multipart POST per alert,
 * with the capture attached as-is (no recompression, no decoding).
 */
@Singleton
class HttpsAlertTransport @Inject constructor(
    private val apiClient: HttpsApiClient,
    private val configStore: HttpsConfigStore,
) : AlertTransport {

    override val isConfigured: Boolean get() = configStore.isComplete

    override suspend fun send(payload: AlertPayload): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isConfigured) {
                if (BuildConfig.DEBUG) {
                    Log.d("HttpsAlertTransport", "Transport not configured, skipping alert")
                }
                return@runCatching
            }
            when (payload) {
                is AlertPayload.TextAlert -> {
                    apiClient.post(
                        kind = HttpsApiClient.KIND_TEXT,
                        message = AlertMessageFormatter.format(payload),
                        timestamp = payload.timestamp
                    ).getOrThrow()
                }

                is AlertPayload.MediaAlert -> {
                    val file = payload.mediaFile
                    val isBlob = file.mimeType == MediaStorageRepositoryImpl.MIME_AGE
                    val bytes = File(file.absolutePath).readBytes()
                    val attachment = if (isBlob) {
                        HttpsApiClient.Attachment(
                            bytes = bytes,
                            fileName = AlertMessageFormatter.opaqueFileName(payload),
                            mimeType = "application/octet-stream"
                        )
                    } else {
                        HttpsApiClient.Attachment(
                            bytes = bytes,
                            fileName = "${payload.timestamp}.${file.absolutePath.substringAfterLast('.')}",
                            mimeType = file.mimeType
                        )
                    }
                    apiClient.post(
                        kind = HttpsApiClient.KIND_FILE,
                        message = if (isBlob) {
                            AlertMessageFormatter.formatOpaque(payload)
                        } else {
                            AlertMessageFormatter.format(payload)
                        },
                        timestamp = payload.timestamp,
                        attachment = attachment
                    ).getOrThrow()
                }
            }
            Unit
        }.onFailure {
            if (BuildConfig.DEBUG) {
                Log.e("HttpsAlertTransport", "Failed to send HTTPS alert", it)
            }
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        apiClient.post(
            kind = HttpsApiClient.KIND_TEST,
            message = "[Neruppu] Connection test",
            timestamp = System.currentTimeMillis()
        )
    }
}
