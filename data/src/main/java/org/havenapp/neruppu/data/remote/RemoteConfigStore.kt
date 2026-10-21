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

package org.havenapp.neruppu.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.havenapp.neruppu.domain.remote.RemoteRole
import javax.inject.Inject
import javax.inject.Singleton

/** Pairing material; the controller's age identity never exists on a guarded phone. */
interface RemoteConfigStore {
    val role: RemoteRole
    val pairing: RemoteProtocol.Pairing?
    /** Controller only. */
    val identity: String?
    fun saveController(pairing: RemoteProtocol.Pairing, identity: String)
    fun saveGuard(pairing: RemoteProtocol.Pairing)
    fun clear()
}

@Singleton
class EncryptedRemoteConfigStore @Inject constructor(
    @ApplicationContext private val context: Context
) : RemoteConfigStore {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "neruppu_remote",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override val role: RemoteRole
        get() = prefs.getString(KEY_ROLE, null)
            ?.let { name -> RemoteRole.entries.firstOrNull { it.name == name } }
            ?: RemoteRole.NONE

    override val pairing: RemoteProtocol.Pairing?
        get() = prefs.getString(KEY_PAIRING, null)?.let {
            RemoteProtocol.decodePairing(it).getOrNull()
        }

    override val identity: String?
        get() = prefs.getString(KEY_IDENTITY, null)

    override fun saveController(pairing: RemoteProtocol.Pairing, identity: String) {
        prefs.edit()
            .putString(KEY_ROLE, RemoteRole.CONTROLLER.name)
            .putString(KEY_PAIRING, RemoteProtocol.encodePairing(pairing))
            .putString(KEY_IDENTITY, identity)
            .commit()
    }

    override fun saveGuard(pairing: RemoteProtocol.Pairing) {
        prefs.edit()
            .putString(KEY_ROLE, RemoteRole.GUARD.name)
            .putString(KEY_PAIRING, RemoteProtocol.encodePairing(pairing))
            .remove(KEY_IDENTITY)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_ROLE = "role"
        const val KEY_PAIRING = "pairing"
        const val KEY_IDENTITY = "identity"
    }
}
