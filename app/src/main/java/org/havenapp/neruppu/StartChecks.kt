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

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** Conditions worth a warning before guarding starts; none of them blocks it. */
enum class StartWarning { NO_INTERNET, NOT_CHARGING }

object StartChecks {

    fun warnings(context: Context, alertChannelActive: Boolean): List<StartWarning> =
        evaluate(
            hasInternet = hasValidatedInternet(context),
            isPluggedIn = isPluggedIn(context),
            alertChannelActive = alertChannelActive
        )

    /**
     * Without an alert channel nothing is sent anyway (offline-first), so the
     * lack of internet is only worth a warning when one is configured.
     */
    fun evaluate(hasInternet: Boolean, isPluggedIn: Boolean, alertChannelActive: Boolean): List<StartWarning> =
        buildList {
            if (alertChannelActive && !hasInternet) add(StartWarning.NO_INTERNET)
            if (!isPluggedIn) add(StartWarning.NOT_CHARGING)
        }

    /** VALIDATED: the system actually reached the internet (not a captive portal). */
    private fun hasValidatedInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Plugged rather than "charging": a full battery on the charger is fine. */
    private fun isPluggedIn(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }
}
