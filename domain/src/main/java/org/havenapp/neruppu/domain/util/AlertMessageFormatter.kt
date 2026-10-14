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

import org.havenapp.neruppu.domain.model.AlertPayload
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AlertMessageFormatter {
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun format(payload: AlertPayload): String {
        val time = TIME_FORMATTER.format(Instant.ofEpochMilli(payload.timestamp))
        return "[Neruppu] Security Alert: ${payload.message} at $time"
    }

    /**
     * Caption for age-encrypted payloads. It travels in clear next to the blob,
     * so it says the minimum: no sensor type ("microphone" vs "motion" tells an
     * observer what happened in the room), only the time.
     */
    fun formatOpaque(payload: AlertPayload): String {
        val time = TIME_FORMATTER.format(Instant.ofEpochMilli(payload.timestamp))
        return "[Neruppu] Alert at $time"
    }

    /** Upload filename for an encrypted blob: reveals nothing about its content. */
    fun opaqueFileName(payload: AlertPayload): String = "${payload.timestamp}.age"
}
