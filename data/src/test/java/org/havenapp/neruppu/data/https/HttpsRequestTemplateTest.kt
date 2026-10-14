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

import org.havenapp.neruppu.domain.util.HttpsBodyEncoding
import org.havenapp.neruppu.domain.util.HttpsPreset
import org.havenapp.neruppu.domain.util.HttpsRequestTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsRequestTemplateTest {

    private fun parse(
        url: String = "https://example.org",
        encoding: HttpsBodyEncoding = HttpsBodyEncoding.MULTIPART,
        headers: String = "",
        fields: String = "message={message}",
        fileField: String = ""
    ) = HttpsRequestTemplate.parse(url, encoding, headers, fields, fileField)

    private fun errors(result: Result<HttpsRequestTemplate>) = result.exceptionOrNull()?.message.orEmpty()

    @Test
    fun `TC-TPL-01 all presets parse`() {
        HttpsPreset.entries.filter { it != HttpsPreset.NERUPPU_RECEIVER }.forEach { p ->
            assertTrue(p.name, HttpsRequestTemplate.parse(p.url, p.encoding, p.headers, p.fields, p.fileField).isSuccess)
        }
        // The receiver preset has no host yet: the user must fill it in.
        val r = HttpsPreset.NERUPPU_RECEIVER
        assertTrue(HttpsRequestTemplate.parse("https://h.example/hook", r.encoding, r.headers, r.fields, r.fileField).isSuccess)
    }

    @Test
    fun `TC-TPL-02 lines are parsed, comments and blanks ignored, values keep separators`() {
        val t = parse(
            headers = "# comment\n\nAuthorization: Bearer a:b\nX-Title:  Neruppu ",
            fields = "token=abc=def\n  user = u1 \n#x=y"
        ).getOrThrow()

        assertEquals(listOf("Authorization" to "Bearer a:b", "X-Title" to "Neruppu"), t.headers)
        assertEquals(listOf("token" to "abc=def", "user" to "u1"), t.fields)
    }

    @Test
    fun `TC-TPL-03 placeholders are substituted, unknown ones are rejected`() {
        val t = parse(fields = "m={message}\nt={timestamp_s}").getOrThrow()
        assertEquals(
            listOf("m" to "hi", "t" to "42"),
            t.renderFields(mapOf("message" to "hi", "timestamp_s" to "42"))
        )
        assertTrue(errors(parse(fields = "m={mesage}")).contains("Unknown placeholder {mesage}"))
    }

    @Test
    fun `TC-TPL-04 invalid configurations are refused with a reason`() {
        assertTrue(errors(parse(url = "http://example.org")).contains("https://"))
        assertTrue(errors(parse(url = "https://example.org/{kind}")).contains("placeholders"))
        assertTrue(errors(parse(fields = "")).contains("At least one field"))
        assertTrue(errors(parse(headers = "no colon here")).contains("Bad header line"))
        assertTrue(errors(parse(headers = "Content-Type: text/plain")).contains("set automatically"))
        assertTrue(errors(parse(fields = "=value")).contains("Bad field line"))
        assertTrue(
            errors(parse(encoding = HttpsBodyEncoding.FORM, fileField = "file"))
                .contains("only be attached with multipart")
        )
    }

    @Test
    fun `TC-TPL-05 captures are sent only with multipart and a file field`() {
        assertTrue(parse(fileField = "file").getOrThrow().sendsFile)
        assertFalse(parse(fileField = "").getOrThrow().sendsFile)
        assertFalse(parse(encoding = HttpsBodyEncoding.JSON).getOrThrow().sendsFile)
    }
}
