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

package org.havenapp.neruppu.domain.remote

import java.net.URI

/**
 * Links put in ntfy alerts that open the remote control app. Anyone who
 * knows the alert topic can publish such a link, so they carry no secret and
 * no URL: a capture is named only by its file name and fetched from the
 * remote's own ntfy alert channel. Nothing happens without an unlock, and a
 * photo request is confirmed first.
 */
sealed class RemoteLink {
    data object Open : RemoteLink()
    data object Photo : RemoteLink()
    data class Capture(val fileName: String) : RemoteLink() {
        /** Event time, from the file name `<timestamp>.age`. */
        val timestamp: Long get() = fileName.substringBefore('.').toLong()
    }

    companion object {
        const val SCHEME = "neruppu"
        const val OPEN = "$SCHEME://remote"
        const val PHOTO = "$SCHEME://photo"
        private val CAPTURE_NAME = Regex("^[0-9]{1,15}\\.age$")

        fun capture(fileName: String): String? =
            if (CAPTURE_NAME.matches(fileName)) "$SCHEME://capture?f=$fileName" else null

        fun parse(link: String?): RemoteLink? {
            val uri = runCatching { URI(link ?: return null) }.getOrNull() ?: return null
            if (uri.scheme != SCHEME) return null
            return when (uri.host) {
                "remote" -> Open
                "photo" -> Photo
                "capture" -> uri.rawQuery
                    ?.split('&')
                    ?.firstNotNullOfOrNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 && p[0] == "f" }?.get(1) }
                    ?.takeIf(CAPTURE_NAME::matches)
                    ?.let(::Capture)
                else -> null
            }
        }
    }
}
