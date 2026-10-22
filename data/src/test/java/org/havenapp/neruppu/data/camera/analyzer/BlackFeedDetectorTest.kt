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

package org.havenapp.neruppu.data.camera.analyzer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackFeedDetectorTest {

    @Test
    fun `TC-CAM-01 uniform black frames for a few seconds flag the feed`() {
        val d = BlackFeedDetector(holdMs = 3_000L)
        d.onFrame(0, 0, 0L)
        d.onFrame(0, 1, 2_000L)
        assertFalse(d.black.value)
        d.onFrame(0, 0, 3_000L)
        assertTrue(d.black.value)
    }

    @Test
    fun `TC-CAM-02 a dark but noisy room is not a blocked camera`() {
        val d = BlackFeedDetector(holdMs = 3_000L)
        for (t in 0L..10_000L step 500L) d.onFrame(2, 18, t)
        assertFalse(d.black.value)
    }

    @Test
    fun `TC-CAM-03 one real frame clears the flag and restarts the wait`() {
        val d = BlackFeedDetector(holdMs = 3_000L)
        d.onFrame(0, 0, 0L)
        d.onFrame(0, 0, 3_000L)
        assertTrue(d.black.value)
        d.onFrame(30, 200, 3_500L)
        assertFalse(d.black.value)
        d.onFrame(0, 0, 4_000L)
        assertFalse(d.black.value)
    }
}
