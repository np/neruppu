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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends each alert as the user-defined HTTPS request (see HttpsRequestTemplate).
 * Captures are attached untouched when the template has a file field.
 */
@Singleton
class HttpsAlertTransport @Inject constructor(
    private val apiClient: HttpsApiClient,
    private val configStore: HttpsConfigStore,
) : AlertTransport {

    override val isConfigured: Boolean get() = configStore.isComplete

    override val deliversMedia: Boolean
        get() = configStore.template().getOrNull()?.sendsFile == true

    override suspend fun send(payload: AlertPayload): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val template = configStore.template().getOrNull()
            if (template == null) {
                if (BuildConfig.DEBUG) {
                    Log.d("HttpsAlertTransport", "Transport not configured, skipping alert")
                }
                return@runCatching
            }
            when (payload) {
                is AlertPayload.TextAlert -> {
                    val text = AlertMessageFormatter.format(payload)
                    apiClient.send(template, vars(KIND_TEXT, text, text, payload.timestamp, "")).getOrThrow()
                }

                is AlertPayload.MediaAlert -> {
                    val file = payload.mediaFile
                    val isBlob = file.mimeType == MediaStorageRepositoryImpl.MIME_AGE
                    val fileName = if (isBlob) {
                        AlertMessageFormatter.opaqueFileName(payload)
                    } else {
                        "${payload.timestamp}.${file.absolutePath.substringAfterLast('.')}"
                    }
                    // Read only when it is actually sent.
                    val attachment = if (template.sendsFile) {
                        HttpsApiClient.Attachment(
                            bytes = File(file.absolutePath).readBytes(),
                            fileName = fileName,
                            mimeType = if (isBlob) "application/octet-stream" else file.mimeType
                        )
                    } else null
                    val message = if (isBlob) {
                        AlertMessageFormatter.formatOpaque(payload)
                    } else {
                        AlertMessageFormatter.format(payload)
                    }
                    apiClient.send(
                        template,
                        vars(
                            kind = KIND_FILE,
                            message = message,
                            details = AlertMessageFormatter.format(payload),
                            timestamp = payload.timestamp,
                            fileName = if (attachment != null) fileName else ""
                        ),
                        attachment
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
        configStore.template().mapCatching { template ->
            val text = "[Neruppu] Connection test"
            apiClient.send(template, vars(KIND_TEST, text, text, System.currentTimeMillis(), "")).getOrThrow()
        }
    }

    companion object {
        const val KIND_TEST = "test"
        const val KIND_TEXT = "text"
        const val KIND_FILE = "file"

        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        fun vars(
            kind: String,
            message: String,
            details: String,
            timestamp: Long,
            fileName: String
        ): Map<String, String> = mapOf(
            "message" to message,
            "details" to details,
            "kind" to kind,
            "time" to TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp)),
            "timestamp_s" to (timestamp / 1000).toString(),
            "timestamp_ms" to timestamp.toString(),
            "filename" to fileName
        )
    }
}
