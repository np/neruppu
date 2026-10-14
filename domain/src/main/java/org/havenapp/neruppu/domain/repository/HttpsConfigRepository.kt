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

package org.havenapp.neruppu.domain.repository

import org.havenapp.neruppu.domain.util.HttpsBodyEncoding

/**
 * User-defined HTTPS request, sent once per alert. Headers and fields are
 * templates (one "Name: value" / "name=value" per line) rendered by
 * [org.havenapp.neruppu.domain.util.HttpsRequestTemplate].
 * Wire format and placeholders: docs/https-transport.md.
 */
interface HttpsConfigRepository {
    var endpointUrl: String
    var bodyEncoding: HttpsBodyEncoding
    var headers: String
    var fields: String
    /** Multipart field carrying the capture; blank = captures are not sent. */
    var fileField: String
    val isComplete: Boolean
    fun clear()
}
