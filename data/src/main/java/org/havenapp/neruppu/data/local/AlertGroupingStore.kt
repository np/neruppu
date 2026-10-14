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

package org.havenapp.neruppu.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.havenapp.neruppu.domain.util.AlertGroupingConfig
import org.havenapp.neruppu.domain.util.AlertGroupingState
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the alert grouping state across upload runs, and reads its settings. */
interface AlertGroupingStore {
    var state: AlertGroupingState
    val config: AlertGroupingConfig
    /** Time the next summary run was scheduled for, to avoid scheduling twice. */
    var scheduledFlushAt: Long
}

@Singleton
class SharedPrefsAlertGroupingStore @Inject constructor(
    @ApplicationContext private val context: Context
) : AlertGroupingStore {
    private val prefs by lazy { context.getSharedPreferences("neruppu_alert_grouping", Context.MODE_PRIVATE) }
    private val settings by lazy { context.getSharedPreferences("neruppu_prefs", Context.MODE_PRIVATE) }

    override var state: AlertGroupingState
        get() = AlertGroupingState(
            incidentLastEventAt = prefs.getLong("incident_last_event_at", -1L).takeIf { it >= 0 },
            windowStart = prefs.getLong("window_start", -1L).takeIf { it >= 0 },
            groupedCount = prefs.getInt("grouped_count", 0),
            firstGroupedAt = prefs.getLong("first_grouped_at", 0L),
            captureEventId = prefs.getLong("capture_event_id", -1L),
            capturePath = prefs.getString("capture_path", null),
            sentAt = prefs.getString("sent_at", "").orEmpty()
                .split(',').mapNotNull { it.toLongOrNull() }
        )
        set(value) {
            prefs.edit()
                .putLong("incident_last_event_at", value.incidentLastEventAt ?: -1L)
                .putLong("window_start", value.windowStart ?: -1L)
                .putInt("grouped_count", value.groupedCount)
                .putLong("first_grouped_at", value.firstGroupedAt)
                .putLong("capture_event_id", value.captureEventId)
                .putString("capture_path", value.capturePath)
                .putString("sent_at", value.sentAt.joinToString(","))
                .commit()
        }

    override val config: AlertGroupingConfig
        get() {
            val windowMs = settings.getInt(PREF_WINDOW_SECONDS, DEFAULT_WINDOW_SECONDS) * 1000L
            return AlertGroupingConfig(
                windowMs = windowMs,
                quietMs = maxOf(QUIET_MS, windowMs),
                hourlyCap = settings.getInt(PREF_HOURLY_CAP, DEFAULT_HOURLY_CAP)
            )
        }

    override var scheduledFlushAt: Long
        get() = prefs.getLong("scheduled_flush_at", 0L)
        set(value) { prefs.edit().putLong("scheduled_flush_at", value).apply() }

    companion object {
        const val PREF_WINDOW_SECONDS = "alert_group_window_seconds"
        const val PREF_HOURLY_CAP = "alert_hourly_cap"
        const val DEFAULT_WINDOW_SECONDS = 120
        const val DEFAULT_HOURLY_CAP = 30
        /** An incident ends after 5 minutes without events (or the window, if longer). */
        const val QUIET_MS = 5 * 60 * 1000L
    }
}
