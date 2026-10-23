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

import android.content.SharedPreferences
import org.havenapp.neruppu.domain.model.SensorType
import kotlin.math.pow

/**
 * The Settings → Sensors switches and sensitivity sliders, as the guarding
 * service applies them. Defaults match what Settings shows before any change.
 */
data class SensorSettings(
    val motion: Boolean = true,
    val sound: Boolean = true,
    val light: Boolean = false,
    val movement: Boolean = true,
    /** Slider 0–1: higher ignores smaller changes in the picture. */
    val motionThreshold: Float = DEFAULT_MOTION_THRESHOLD,
    /** Slider 0–1: higher needs a louder noise. */
    val soundThreshold: Float = DEFAULT_SOUND_THRESHOLD
) {
    fun allows(type: SensorType): Boolean = when (type) {
        SensorType.CAMERA_MOTION -> motion
        SensorType.MICROPHONE -> sound
        SensorType.LIGHT -> light
        SensorType.ACCELEROMETER -> movement
        SensorType.BAROMETER, SensorType.POWER -> true
    }

    /** Per-pixel change the motion analyzer counts, as before (5–30). */
    val motionAnalyzerSensitivity: Int get() = (motionThreshold * 30).toInt().coerceIn(5, 30)

    /**
     * Noise spike above the background (RMS) that triggers: 50 to 2000 on a
     * log scale, 200 at the default position (the former fixed value).
     */
    val soundSpikeRms: Float get() = 50f * 40f.pow(soundThreshold.coerceIn(0f, 1f))

    companion object {
        const val KEY_MOTION = "motion_enabled"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_LIGHT = "light_enabled"
        const val KEY_MOVEMENT = "accelerometer_enabled"
        const val KEY_MOTION_THRESHOLD = "motion_sensitivity"
        const val KEY_SOUND_THRESHOLD = "sound_threshold"
        const val DEFAULT_MOTION_THRESHOLD = 0.5f
        /** 50 × 40^0.376 ≈ 200 RMS. */
        const val DEFAULT_SOUND_THRESHOLD = 0.376f

        /** Changing one of these while guarding restarts the sensors. */
        val KEYS = setOf(KEY_MOTION, KEY_SOUND, KEY_LIGHT, KEY_MOVEMENT, KEY_MOTION_THRESHOLD, KEY_SOUND_THRESHOLD)

        fun from(prefs: SharedPreferences) = SensorSettings(
            motion = prefs.getBoolean(KEY_MOTION, true),
            sound = prefs.getBoolean(KEY_SOUND, true),
            light = prefs.getBoolean(KEY_LIGHT, false),
            movement = prefs.getBoolean(KEY_MOVEMENT, true),
            motionThreshold = prefs.getFloat(KEY_MOTION_THRESHOLD, DEFAULT_MOTION_THRESHOLD),
            soundThreshold = prefs.getFloat(KEY_SOUND_THRESHOLD, DEFAULT_SOUND_THRESHOLD)
        )
    }
}
