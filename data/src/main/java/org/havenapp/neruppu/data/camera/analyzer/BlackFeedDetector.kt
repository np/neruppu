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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notices a camera feed that is uniformly black for a while. With camera
 * access turned off in quick settings Android keeps the camera "working"
 * but sends solid black frames; a covered lens looks the same. A merely dark
 * room still has sensor noise, so the frame is not uniform.
 */
class BlackFeedDetector(
    private val holdMs: Long = 3_000L,
    private val maxLuma: Int = 24,
    private val maxSpread: Int = 3
) {
    private val _black = MutableStateFlow(false)
    val black: StateFlow<Boolean> = _black.asStateFlow()
    private var blackSince = -1L

    /** [min] and [max]: luma range (0–255) of the sampled pixels of one frame. */
    fun onFrame(min: Int, max: Int, now: Long) {
        if (max <= maxLuma && max - min <= maxSpread) {
            if (blackSince < 0) blackSince = now
            if (now - blackSince >= holdMs) _black.value = true
        } else {
            blackSince = -1L
            _black.value = false
        }
    }

    fun reset() {
        blackSince = -1L
        _black.value = false
    }
}
