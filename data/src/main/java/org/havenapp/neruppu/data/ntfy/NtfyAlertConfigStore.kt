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

package org.havenapp.neruppu.data.ntfy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.NtfyAlertConfigRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NtfyAlertConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertTargetRepository: AlertTargetRepository
) : NtfyAlertConfigRepository {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "neruppu_ntfy_alerts",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override var server: String
        get() = prefs.getString(KEY_SERVER, null) ?: NtfyAlertConfigRepository.DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()

    override var topic: String
        get() = prefs.getString(KEY_TOPIC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOPIC, value.trim()).apply()

    override var accessToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    override var attachCaptures: Boolean
        get() = prefs.getBoolean(KEY_ATTACH, true)
        set(value) = prefs.edit().putBoolean(KEY_ATTACH, value).apply()

    override val isComplete: Boolean
        get() = NtfyAlertConfigRepository.isValidServer(server) && NtfyAlertConfigRepository.isValidTopic(topic)

    override fun clear() {
        prefs.edit().clear().apply()
        alertTargetRepository.clearActiveTarget()
    }

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_TOPIC = "topic"
        const val KEY_TOKEN = "token"
        const val KEY_ATTACH = "attach_captures"
    }
}
