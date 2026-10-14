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

/**
 * Decides which events go out as remote alerts, so a burst of activity does
 * not flood the alert channel. Pure function of (state, config, time); the
 * caller persists the state between upload runs.
 *
 * - The first event of an incident is sent at once.
 * - Later events of the same incident are grouped; at most once per
 *   [AlertGroupingConfig.windowMs] a summary goes out ("+N events"), with
 *   the first grouped capture of that window.
 * - An incident ends after [AlertGroupingConfig.quietMs] without events.
 * - At most [AlertGroupingConfig.hourlyCap] alerts per rolling hour
 *   (0 = unlimited); anything over the cap is grouped and summarised once
 *   the cap allows it again.
 *
 * Grouped captures are never sent individually: they stay on the device.
 */
object AlertGrouping {

    const val HOUR_MS = 60 * 60 * 1000L

    enum class Decision { SEND, GROUP }

    data class FlushPlan(
        val count: Int,
        val since: Long,
        val captureEventId: Long,
        val capturePath: String?
    )

    fun canSend(state: AlertGroupingState, config: AlertGroupingConfig, now: Long): Boolean =
        config.hourlyCap <= 0 || state.sentAt.count { it > now - HOUR_MS } < config.hourlyCap

    /** Classifies one event. The returned state must only be kept if a SEND succeeded. */
    fun onEvent(
        state: AlertGroupingState,
        config: AlertGroupingConfig,
        eventTs: Long,
        now: Long,
        eventId: Long,
        capturePath: String?
    ): Pair<Decision, AlertGroupingState> {
        val last = state.incidentLastEventAt
        val newIncident = last == null || eventTs - last > config.quietMs
        val lastEventAt = maxOf(last ?: eventTs, eventTs)
        val grouping = config.windowMs > 0

        if ((newIncident || !grouping) && canSend(state, config, now)) {
            return Decision.SEND to state.copy(
                incidentLastEventAt = lastEventAt,
                // A new incident starts a fresh summary window at its first event.
                windowStart = if (newIncident) eventTs else state.windowStart
            )
        }

        val firstInWindow = state.groupedCount == 0
        return Decision.GROUP to state.copy(
            incidentLastEventAt = lastEventAt,
            windowStart = state.windowStart ?: eventTs,
            groupedCount = state.groupedCount + 1,
            firstGroupedAt = if (firstInWindow) eventTs else state.firstGroupedAt,
            captureEventId = if (state.captureEventId < 0 && capturePath != null) eventId else state.captureEventId,
            capturePath = state.capturePath ?: capturePath
        )
    }

    /** Summary to send now, or null if nothing is due. */
    fun flushPlan(state: AlertGroupingState, config: AlertGroupingConfig, now: Long): FlushPlan? {
        if (state.groupedCount == 0) return null
        if (now < (state.windowStart ?: now) + config.windowMs) return null
        if (!canSend(state, config, now)) return null
        return FlushPlan(state.groupedCount, state.firstGroupedAt, state.captureEventId, state.capturePath)
    }

    /** State after the summary was sent: a new window starts now. */
    fun afterFlush(state: AlertGroupingState, now: Long): AlertGroupingState =
        recordSend(state, now).copy(
            windowStart = now,
            groupedCount = 0,
            firstGroupedAt = 0L,
            captureEventId = -1L,
            capturePath = null
        )

    fun recordSend(state: AlertGroupingState, now: Long): AlertGroupingState =
        state.copy(sentAt = state.sentAt.filter { it > now - HOUR_MS } + now)

    /** When the next summary can go out, or null if nothing is waiting. */
    fun nextFlushAt(state: AlertGroupingState, config: AlertGroupingConfig, now: Long): Long? {
        if (state.groupedCount == 0) return null
        val windowEnd = (state.windowStart ?: now) + config.windowMs
        val capFree = if (canSend(state, config, now)) now else {
            // The oldest send that still counts leaves the window first.
            val recent = state.sentAt.filter { it > now - HOUR_MS }.sorted()
            recent[recent.size - config.hourlyCap] + HOUR_MS + 1
        }
        return maxOf(windowEnd, capFree)
    }
}

data class AlertGroupingConfig(
    /** Summary window; 0 disables grouping (the hourly cap still applies). */
    val windowMs: Long,
    /** An incident ends after this long without events. */
    val quietMs: Long,
    /** Alerts per rolling hour; 0 = unlimited. */
    val hourlyCap: Int
)

data class AlertGroupingState(
    /** Time of the last event of the current incident; null when there is none. */
    val incidentLastEventAt: Long? = null,
    /** Start of the current summary window; null before the first event. */
    val windowStart: Long? = null,
    val groupedCount: Int = 0,
    val firstGroupedAt: Long = 0L,
    val captureEventId: Long = -1L,
    val capturePath: String? = null,
    val sentAt: List<Long> = emptyList()
)
