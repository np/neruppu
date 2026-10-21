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

package org.havenapp.neruppu.domain.util

enum class HttpsBodyEncoding { MULTIPART, FORM, JSON }

/**
 * A user-defined HTTPS request: static URL, templated headers and fields.
 *
 * Text format, one entry per line, blank lines and lines starting with '#'
 * ignored:
 *   headers: "Name: value"      fields: "name=value"
 *
 * Values may contain {placeholders}, see [PLACEHOLDERS]. Placeholders are
 * substituted as plain text; encoding (form, multipart, JSON string) is done
 * by the client afterwards, so values never need escaping here.
 */
data class HttpsRequestTemplate(
    val url: String,
    val encoding: HttpsBodyEncoding,
    val headers: List<Pair<String, String>>,
    val fields: List<Pair<String, String>>,
    val fileField: String
) {
    /** True when captures are attached: only multipart bodies carry files. */
    val sendsFile: Boolean get() = encoding == HttpsBodyEncoding.MULTIPART && fileField.isNotBlank()

    fun renderHeaders(vars: Map<String, String>) = headers.map { (k, v) -> k to substitute(v, vars) }
    fun renderFields(vars: Map<String, String>) = fields.map { (k, v) -> k to substitute(v, vars) }

    companion object {
        /** Name -> meaning, shown in the docs and used for validation. */
        val PLACEHOLDERS = linkedMapOf(
            "message" to "caption; for an encrypted capture only \"[Neruppu] Alert at <time>\"",
            "details" to "full event description, including the sensor type (reveals what happened)",
            "kind" to "test, text or file",
            "time" to "event time, yyyy-MM-dd HH:mm:ss (device time zone)",
            "timestamp_s" to "event time, Unix seconds",
            "timestamp_ms" to "event time, Unix milliseconds",
            "filename" to "capture file name (<timestamp>.age), empty without capture"
        )

        private val PLACEHOLDER = Regex("""\{([a-z_]+)\}""")
        private val HEADER_NAME = Regex("""^[A-Za-z0-9!#$%&'*+.^_`|~-]+$""")
        private val FIELD_NAME = Regex("""^[^\s=]+$""")
        // Set by the HTTP stack itself; overriding them breaks the request.
        private val RESERVED_HEADERS = setOf("content-type", "content-length", "host", "transfer-encoding")

        fun substitute(value: String, vars: Map<String, String>): String =
            PLACEHOLDER.replace(value) { m -> vars[m.groupValues[1]] ?: m.value }

        private fun lines(text: String) = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        /** Parses and validates; returns the template or the list of problems. */
        fun parse(
            url: String,
            encoding: HttpsBodyEncoding,
            headers: String,
            fields: String,
            fileField: String
        ): Result<HttpsRequestTemplate> {
            val errors = mutableListOf<String>()
            if (!HttpsEndpointValidator.isValid(url)) errors += "URL must be an https:// URL"
            if (PLACEHOLDER.containsMatchIn(url)) errors += "URL cannot contain placeholders"

            val parsedHeaders = lines(headers).mapNotNull { line ->
                val i = line.indexOf(':')
                val name = if (i > 0) line.substring(0, i).trim() else ""
                when {
                    i <= 0 || !HEADER_NAME.matches(name) -> { errors += "Bad header line: $line"; null }
                    name.lowercase() in RESERVED_HEADERS -> { errors += "Header $name is set automatically"; null }
                    else -> name to line.substring(i + 1).trim()
                }
            }
            val parsedFields = lines(fields).mapNotNull { line ->
                val i = line.indexOf('=')
                val name = if (i > 0) line.substring(0, i).trim() else ""
                if (i <= 0 || !FIELD_NAME.matches(name)) { errors += "Bad field line: $line"; null }
                else name to line.substring(i + 1).trim()
            }
            if (parsedFields.isEmpty()) errors += "At least one field is required"

            val file = fileField.trim()
            if (file.isNotEmpty()) {
                if (!FIELD_NAME.matches(file)) errors += "Bad file field name: $file"
                if (encoding != HttpsBodyEncoding.MULTIPART) errors += "Captures can only be attached with multipart encoding"
            }

            (parsedHeaders + parsedFields).forEach { (_, v) ->
                PLACEHOLDER.findAll(v).map { it.groupValues[1] }
                    .filter { it !in PLACEHOLDERS }
                    .forEach { errors += "Unknown placeholder {$it}" }
            }

            return if (errors.isEmpty()) {
                Result.success(HttpsRequestTemplate(url.trim(), encoding, parsedHeaders, parsedFields, file))
            } else {
                Result.failure(IllegalArgumentException(errors.distinct().joinToString("\n")))
            }
        }
    }
}

/** Ready-made templates the settings screen can fill in. */
enum class HttpsPreset(
    val label: String,
    val url: String,
    val encoding: HttpsBodyEncoding,
    val headers: String,
    val fields: String,
    val fileField: String
) {
    NERUPPU_RECEIVER(
        label = "Neruppu receiver",
        url = "https://",
        encoding = HttpsBodyEncoding.MULTIPART,
        headers = "Authorization: Bearer YOUR_TOKEN",
        fields = "kind={kind}\ntimestamp={timestamp_ms}\nmessage={message}",
        fileField = "file"
    ),

    // https://pushover.net/api: attachments must be images, so the encrypted
    // capture is not sent; the notification links nothing readable.
    PUSHOVER(
        label = "Pushover",
        url = "https://api.pushover.net/1/messages.json",
        encoding = HttpsBodyEncoding.FORM,
        headers = "",
        fields = "token=YOUR_APP_TOKEN\nuser=YOUR_USER_KEY\ntitle=Neruppu\nmessage={message}\ntimestamp={timestamp_s}\npriority=1",
        fileField = ""
    )
}
