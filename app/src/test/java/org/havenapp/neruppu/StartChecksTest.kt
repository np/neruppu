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

package org.havenapp.neruppu

import org.junit.Assert.assertEquals
import org.junit.Test

class StartChecksTest {

    @Test
    fun `TC-START-01 all good means no dialog`() {
        assertEquals(emptyList<StartWarning>(), StartChecks.evaluate(hasInternet = true, isPluggedIn = true, alertChannelActive = true))
    }

    @Test
    fun `TC-START-02 both problems are reported together`() {
        assertEquals(
            listOf(StartWarning.NO_INTERNET, StartWarning.NOT_CHARGING),
            StartChecks.evaluate(hasInternet = false, isPluggedIn = false, alertChannelActive = true)
        )
    }

    @Test
    fun `TC-START-03 no internet does not matter without an alert channel`() {
        assertEquals(
            emptyList<StartWarning>(),
            StartChecks.evaluate(hasInternet = false, isPluggedIn = true, alertChannelActive = false)
        )
        assertEquals(
            listOf(StartWarning.NOT_CHARGING),
            StartChecks.evaluate(hasInternet = false, isPluggedIn = false, alertChannelActive = false)
        )
    }
}
