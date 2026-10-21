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

package org.havenapp.neruppu.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * Minimal ntfy client (https://docs.ntfy.sh): publish text, publish a file
 * as attachment, and subscribe to a topic as a JSON stream.
 */
class NtfyClient(private val client: HttpClient) {

    @Serializable
    data class Event(
        val id: String = "",
        val time: Long = 0,
        val event: String = "",
        val message: String? = null,
        val attachment: Attachment? = null
    )

    @Serializable
    data class Attachment(val name: String = "", val url: String = "", val size: Long? = null)

    suspend fun publish(server: String, topic: String, text: String) {
        val response = client.post("$server/$topic") {
            setBody(TextContent(text, ContentType.Text.Plain))
        }
        check(response.status.isSuccess()) { "ntfy publish failed: ${response.status}" }
    }

    /** The file becomes an ntfy attachment; [text] is the message shown with it. */
    suspend fun publishFile(server: String, topic: String, bytes: ByteArray, fileName: String, text: String) {
        val response = client.put("$server/$topic") {
            header("Filename", fileName)
            header("Message", text)
            setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
        }
        check(response.status.isSuccess()) { "ntfy upload failed: ${response.status}" }
    }

    suspend fun download(url: String, maxBytes: Long = MAX_DOWNLOAD_BYTES): ByteArray {
        val response = client.get(url)
        check(response.status.isSuccess()) { "ntfy download failed: ${response.status}" }
        return response.readBytes().also { check(it.size <= maxBytes) { "Attachment too large" } }
    }

    /**
     * Messages published to [topic] from now on (or after message [sinceId]),
     * until the connection drops; the flow then completes or fails and the
     * caller reconnects. ntfy sends a keepalive every 45 s.
     */
    fun subscribe(server: String, topic: String, sinceId: String?): Flow<Event> = flow {
        client.prepareGet("$server/$topic/json") {
            if (sinceId != null) parameter("since", sinceId)
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = STREAM_IDLE_TIMEOUT_MS
            }
        }.execute { response ->
            check(response.status.isSuccess()) { "ntfy subscribe failed: ${response.status}" }
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val event = runCatching { RemoteProtocol.json.decodeFromString(Event.serializer(), line) }.getOrNull()
                if (event?.event == "message") emit(event)
            }
        }
    }

    companion object {
        private const val STREAM_IDLE_TIMEOUT_MS = 120_000L
        private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024
    }
}
