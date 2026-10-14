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

package org.havenapp.neruppu.data.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import neruppuage.Neruppuage
import org.havenapp.neruppu.domain.repository.EncryptionKeyRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the age recipient (an "age1..." X25519 PUBLIC key) that captures are
 * encrypted to.
 *
 * Note the deliberate difference from MatrixConfigStore: there is no silent
 * fallback to plain SharedPreferences. If the keystore is unavailable we throw,
 * because a security control that quietly degrades is worse than one that
 * fails loudly. The recipient is a public key, so this is belt-and-braces —
 * but the same store would be reused for anything secret later.
 */
@Singleton
class AgeRecipientStore @Inject constructor(
    @ApplicationContext private val context: Context
) : EncryptionKeyRepository {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "neruppu_age_recipient",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override val recipients: String
        get() = prefs.getString(KEY_RECIPIENTS, "").orEmpty()

    override val isConfigured: Boolean get() = recipients.isNotBlank()

    /**
     * Validates through the age library itself rather than a regex, so what we
     * accept here is exactly what will encrypt successfully later.
     *
     * Returns false (and stores nothing) if any key is rejected.
     */
    override fun setRecipients(keys: List<String>): Boolean {
        val cleaned = keys.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return false
        if (cleaned.any { !Neruppuage.isValidRecipient(it) }) return false
        prefs.edit().putString(KEY_RECIPIENTS, cleaned.joinToString("\n")).apply()
        return true
    }

    override fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_RECIPIENTS = "recipients"
    }
}
