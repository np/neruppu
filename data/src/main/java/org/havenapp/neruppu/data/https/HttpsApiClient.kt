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
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.havenapp.neruppu.domain.util.HttpsBodyEncoding
import org.havenapp.neruppu.domain.util.HttpsEndpointValidator
import org.havenapp.neruppu.domain.util.HttpsRequestTemplate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends one alert as the user-defined request: POST to a static https URL,
 * templated headers and fields, encoded as multipart, form or JSON.
 * Format and placeholders: docs/https-transport.md.
 */
@Singleton
class HttpsApiClient @Inject constructor(
    private val client: HttpClient
) {
    class Attachment(val bytes: ByteArray, val fileName: String, val mimeType: String)

    suspend fun send(
        template: HttpsRequestTemplate,
        vars: Map<String, String>,
        attachment: Attachment? = null
    ): Result<Unit> = runCatching {
        // Re-checked at send time: never send credentials over anything but https.
        require(HttpsEndpointValidator.isValid(template.url)) { "Endpoint URL is not a valid https:// URL" }

        val fields = template.renderFields(vars)
        val response: HttpResponse = client.post(template.url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            template.renderHeaders(vars).forEach { (name, value) -> header(name, value) }

            when (template.encoding) {
                HttpsBodyEncoding.MULTIPART -> setBody(MultiPartFormDataContent(formData {
                    fields.forEach { (name, value) -> append(name, value) }
                    if (attachment != null && template.sendsFile) {
                        append(template.fileField, attachment.bytes, Headers.build {
                            append(HttpHeaders.ContentType, attachment.mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"${attachment.fileName}\"")
                        })
                    }
                }))

                HttpsBodyEncoding.FORM -> setBody(FormDataContent(Parameters.build {
                    fields.forEach { (name, value) -> append(name, value) }
                }))

                // Flat object of strings; a repeated name keeps its last value.
                HttpsBodyEncoding.JSON -> setBody(TextContent(
                    JsonObject(fields.associate { (name, value) -> name to JsonPrimitive(value) }).toString(),
                    ContentType.Application.Json
                ))
            }
        }
        // Redirects are not followed for POST, so a 3xx lands here as a failure.
        if (!response.status.isSuccess()) {
            val detail = runCatching { response.bodyAsText() }.getOrDefault("")
                .replace(Regex("\\s+"), " ").trim().take(MAX_ERROR_DETAIL)
            error("Endpoint answered ${response.status}" + if (detail.isNotEmpty()) ": $detail" else "")
        }
    }

    companion object {
        // No version: the endpoint learns nothing beyond "this is Neruppu".
        private const val USER_AGENT = "Neruppu"
        private const val MAX_ERROR_DETAIL = 200
    }
}
