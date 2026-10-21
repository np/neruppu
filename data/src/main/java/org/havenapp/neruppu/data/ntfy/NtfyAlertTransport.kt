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

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.havenapp.neruppu.data.BuildConfig
import org.havenapp.neruppu.data.storage.MediaStorageRepositoryImpl
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.repository.NtfyAlertConfigRepository
import org.havenapp.neruppu.domain.transport.AlertTransport
import org.havenapp.neruppu.domain.util.AlertMessageFormatter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alerts as ntfy notifications (https://docs.ntfy.sh/publish/): text in the
 * body, title/priority/tags as headers; captures as attachments (PUT with
 * Filename), still age-encrypted, so ntfy never sees a photo or a sound.
 */
@Singleton
class NtfyAlertTransport @Inject constructor(
    private val config: NtfyAlertConfigRepository,
    private val client: HttpClient
) : AlertTransport {

    override val isConfigured: Boolean get() = config.isComplete

    override val deliversMedia: Boolean get() = config.attachCaptures

    override suspend fun send(payload: AlertPayload): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isConfigured) return@runCatching
            when (payload) {
                is AlertPayload.TextAlert -> postText(AlertMessageFormatter.format(payload))
                is AlertPayload.MediaAlert -> {
                    val file = payload.mediaFile
                    val isBlob = file.mimeType == MediaStorageRepositoryImpl.MIME_AGE
                    val message = if (isBlob) AlertMessageFormatter.formatOpaque(payload) else AlertMessageFormatter.format(payload)
                    if (config.attachCaptures) {
                        val name = if (isBlob) AlertMessageFormatter.opaqueFileName(payload)
                        else "${payload.timestamp}.${file.absolutePath.substringAfterLast('.')}"
                        putFile(File(file.absolutePath).readBytes(), name, message)
                    } else {
                        postText(message)
                    }
                }
            }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("NtfyAlertTransport", "ntfy alert failed: ${it.message}")
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { postText("[Neruppu] Connection test: alerts will arrive here.") }
    }

    private val url get() = "${config.server.trimEnd('/')}/${config.topic}"

    private fun HttpRequestBuilder.common() {
        header("Title", "Neruppu")
        header("Priority", "high")
        header("Tags", "rotating_light")
        val token = config.accessToken
        if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun postText(text: String) {
        check(client.post(url) {
            common()
            setBody(TextContent(text, ContentType.Text.Plain))
        })
    }

    private suspend fun putFile(bytes: ByteArray, fileName: String, message: String) {
        check(client.put(url) {
            common()
            header("Filename", fileName)
            header("Message", message)
            setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
        })
    }

    private suspend fun check(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            // ntfy explains errors in JSON, e.g. {"code":40101,"error":"unauthorized"}
            val detail = runCatching { response.bodyAsText() }.getOrDefault("").take(200)
            error("ntfy answered ${response.status}" + if (detail.isNotBlank()) ": $detail" else "")
        }
    }
}
