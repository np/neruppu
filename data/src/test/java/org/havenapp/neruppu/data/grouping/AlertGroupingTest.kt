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

package org.havenapp.neruppu.data.grouping

import org.havenapp.neruppu.domain.util.AlertGrouping
import org.havenapp.neruppu.domain.util.AlertGrouping.Decision.GROUP
import org.havenapp.neruppu.domain.util.AlertGrouping.Decision.SEND
import org.havenapp.neruppu.domain.util.AlertGroupingConfig
import org.havenapp.neruppu.domain.util.AlertGroupingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlertGroupingTest {

    private val min = 60_000L
    private val config = AlertGroupingConfig(windowMs = 2 * min, quietMs = 5 * min, hourlyCap = 30)

    /** Feeds events at the given times (send always succeeds); returns decisions and final state. */
    private fun run(
        times: List<Long>,
        cfg: AlertGroupingConfig = config,
        start: AlertGroupingState = AlertGroupingState()
    ): Pair<List<AlertGrouping.Decision>, AlertGroupingState> {
        var state = start
        val decisions = times.mapIndexed { i, t ->
            AlertGrouping.flushPlan(state, cfg, t)?.let { state = AlertGrouping.afterFlush(state, t) }
            val (d, next) = AlertGrouping.onEvent(state, cfg, t, t, i.toLong(), "/c/$i.age")
            state = if (d == SEND) AlertGrouping.recordSend(next, t) else next
            d
        }
        return decisions to state
    }

    @Test
    fun `TC-GRP-01 a burst sends the first event and groups the rest`() {
        val (decisions, state) = run(listOf(0L, 3_000, 6_000, 9_000, 30_000))

        assertEquals(listOf(SEND, GROUP, GROUP, GROUP, GROUP), decisions)
        assertEquals(4, state.groupedCount)
        assertEquals(3_000L, state.firstGroupedAt)
        assertEquals(1L, state.captureEventId)
        assertEquals("/c/1.age", state.capturePath)
    }

    @Test
    fun `TC-GRP-02 summary is due at the end of the window, then a new window starts`() {
        val (_, state) = run(listOf(0L, 3_000, 6_000))

        assertNull(AlertGrouping.flushPlan(state, config, 2 * min - 1))
        val plan = AlertGrouping.flushPlan(state, config, 2 * min)
        assertNotNull(plan)
        assertEquals(2, plan!!.count)
        assertEquals(1L, plan.captureEventId)
        assertEquals(2 * min, AlertGrouping.nextFlushAt(state, config, 10_000))

        val after = AlertGrouping.afterFlush(state, 2 * min)
        assertEquals(0, after.groupedCount)
        assertEquals(-1L, after.captureEventId)
        assertNull(AlertGrouping.nextFlushAt(after, config, 2 * min))
        assertEquals(2, after.sentAt.size)
    }

    @Test
    fun `TC-GRP-03 continuous activity gives one summary per window`() {
        // An event every 10 s for 10 minutes: 1 immediate alert + 1 summary per 2 min.
        val (decisions, _) = run((0L until 10 * min step 10_000).toList())
        var summaries = 0
        var state = AlertGroupingState()
        for ((i, t) in (0L until 10 * min step 10_000).withIndex()) {
            AlertGrouping.flushPlan(state, config, t)?.let { state = AlertGrouping.afterFlush(state, t); summaries++ }
            val (d, next) = AlertGrouping.onEvent(state, config, t, t, i.toLong(), null)
            state = if (d == SEND) AlertGrouping.recordSend(next, t) else next
        }
        assertEquals(1, decisions.count { it == SEND })
        assertEquals(4, summaries)
    }

    @Test
    fun `TC-GRP-04 a quiet period ends the incident and the next event is sent at once`() {
        val (decisions, _) = run(listOf(0L, 3_000, 3_000 + 5 * min + 1))
        assertEquals(listOf(SEND, GROUP, SEND), decisions)
    }

    @Test
    fun `TC-GRP-05 hourly cap groups even new incidents, and says when it frees up`() {
        val capped = config.copy(hourlyCap = 2, windowMs = 0)
        // Grouping off: every event is its own alert until the cap.
        val (decisions, state) = run(listOf(0L, 10 * min, 20 * min, 30 * min), capped)

        assertEquals(listOf(SEND, SEND, GROUP, GROUP), decisions)
        assertNull(AlertGrouping.flushPlan(state, capped, 30 * min))
        // The send at t=0 leaves the rolling hour just after 60 min.
        assertEquals(60 * min + 1, AlertGrouping.nextFlushAt(state, capped, 30 * min))
        assertEquals(2, AlertGrouping.flushPlan(state, capped, 60 * min + 1)!!.count)
    }

    @Test
    fun `TC-GRP-06 grouping off sends every event while under the cap`() {
        val (decisions, _) = run(listOf(0L, 1_000, 2_000), config.copy(windowMs = 0, hourlyCap = 0))
        assertEquals(listOf(SEND, SEND, SEND), decisions)
    }
}
