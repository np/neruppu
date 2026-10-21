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
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import org.havenapp.neruppu.domain.remote.RemoteRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pairing material. On a remote, the pairing and the age identity are sealed
 * with an auth-bound Keystore key ([SecretVault]): reading them throws
 * RemoteLockedException until the user unlocks. On a guarded phone the
 * pairing stays readable at any time (it guards unattended) and there is no
 * identity.
 */
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
    @ApplicationContext private val context: Context,
    private val vault: SecretVault
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

    @Serializable
    private data class ControllerSecrets(val pairing: String, val identity: String)

    override val role: RemoteRole
        get() = prefs.getString(KEY_ROLE, null)
            ?.let { name -> RemoteRole.entries.firstOrNull { it.name == name } }
            ?: RemoteRole.NONE

    private fun controllerSecrets(): ControllerSecrets? {
        val sealed = prefs.getString(KEY_CONTROLLER_SECRETS, null) ?: return migrateLegacyController()
        val plain = vault.open(Base64.decode(sealed, Base64.NO_WRAP))
        return RemoteProtocol.json.decodeFromString(ControllerSecrets.serializer(), String(plain))
    }

    /**
     * A remote paired before the biometric lock kept its secrets readable
     * without unlocking: seal them now (needs an unlock, like any access).
     */
    private fun migrateLegacyController(): ControllerSecrets? {
        val pairing = prefs.getString(KEY_PAIRING, null) ?: return null
        val identity = prefs.getString(KEY_LEGACY_IDENTITY, null) ?: return null
        val pairingValue = RemoteProtocol.decodePairing(pairing).getOrNull() ?: return null
        saveController(pairingValue, identity)
        return ControllerSecrets(pairing, identity)
    }

    override val pairing: RemoteProtocol.Pairing?
        get() = when (role) {
            RemoteRole.CONTROLLER -> controllerSecrets()?.pairing
            RemoteRole.GUARD -> prefs.getString(KEY_PAIRING, null)
            RemoteRole.NONE -> null
        }?.let { RemoteProtocol.decodePairing(it).getOrNull() }

    override val identity: String?
        get() = if (role == RemoteRole.CONTROLLER) controllerSecrets()?.identity else null

    override fun saveController(pairing: RemoteProtocol.Pairing, identity: String) {
        val secrets = ControllerSecrets(RemoteProtocol.encodePairing(pairing), identity)
        val sealed = vault.seal(RemoteProtocol.json.encodeToString(ControllerSecrets.serializer(), secrets).toByteArray())
        prefs.edit()
            .clear()
            .putString(KEY_ROLE, RemoteRole.CONTROLLER.name)
            .putString(KEY_CONTROLLER_SECRETS, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .commit()
    }

    override fun saveGuard(pairing: RemoteProtocol.Pairing) {
        prefs.edit()
            .clear()
            .putString(KEY_ROLE, RemoteRole.GUARD.name)
            .putString(KEY_PAIRING, RemoteProtocol.encodePairing(pairing))
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
        vault.delete()
    }

    private companion object {
        const val KEY_ROLE = "role"
        const val KEY_PAIRING = "pairing"
        const val KEY_CONTROLLER_SECRETS = "controller_secrets"
        const val KEY_LEGACY_IDENTITY = "identity"
    }
}
