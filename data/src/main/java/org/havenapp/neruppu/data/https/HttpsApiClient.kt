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
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import org.havenapp.neruppu.domain.util.HttpsEndpointValidator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts alerts to a user-run HTTPS endpoint as multipart/form-data.
 * Wire format: docs/https-transport.md.
 */
@Singleton
class HttpsApiClient @Inject constructor(
    private val configStore: HttpsConfigStore,
    private val client: HttpClient
) {
    class Attachment(val bytes: ByteArray, val fileName: String, val mimeType: String)

    suspend fun post(
        kind: String,
        message: String,
        timestamp: Long,
        attachment: Attachment? = null
    ): Result<Unit> = runCatching {
        val url = configStore.endpointUrl
        // Re-checked at send time: never send the token over anything but https.
        require(HttpsEndpointValidator.isValid(url)) { "HTTPS endpoint URL is not a valid https:// URL" }
        val token = configStore.authToken

        val response: HttpResponse = client.post(url) {
            if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.UserAgent, USER_AGENT)
            setBody(MultiPartFormDataContent(formData {
                append("kind", kind)
                append("timestamp", timestamp.toString())
                append("message", message)
                if (attachment != null) {
                    append("file", attachment.bytes, Headers.build {
                        append(HttpHeaders.ContentType, attachment.mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"${attachment.fileName}\"")
                    })
                }
            }))
        }
        // Redirects are not followed for POST, so a 3xx lands here as a failure.
        if (!response.status.isSuccess()) {
            error("HTTPS endpoint answered ${response.status}")
        }
    }

    companion object {
        const val KIND_TEST = "test"
        const val KIND_TEXT = "text"
        const val KIND_FILE = "file"
        // No version: the endpoint learns nothing beyond "this is Neruppu".
        private const val USER_AGENT = "Neruppu"
    }
}
