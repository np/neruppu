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

package org.havenapp.neruppu.service

import org.havenapp.neruppu.domain.model.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorSettingsTest {

    @Test
    fun `TC-SENS-01 only the enabled sensors raise events`() {
        val onlyMotion = SensorSettings(motion = true, sound = false, light = false, movement = false)
        assertTrue(onlyMotion.allows(SensorType.CAMERA_MOTION))
        assertFalse(onlyMotion.allows(SensorType.MICROPHONE))
        assertFalse(onlyMotion.allows(SensorType.LIGHT))
        assertFalse(onlyMotion.allows(SensorType.ACCELEROMETER))
    }

    @Test
    fun `TC-SENS-02 defaults match what Settings shows`() {
        val d = SensorSettings()
        assertTrue(d.motion && d.sound && d.movement)
        assertFalse(d.light)
        // The default sound position keeps the former fixed trigger level.
        assertEquals(200f, d.soundSpikeRms, 2f)
        assertEquals(15, d.motionAnalyzerSensitivity)
    }

    @Test
    fun `TC-SENS-03 the sound slider spans quiet to loud`() {
        assertEquals(50f, SensorSettings(soundThreshold = 0f).soundSpikeRms, 0.1f)
        assertEquals(2000f, SensorSettings(soundThreshold = 1f).soundSpikeRms, 1f)
    }
}
