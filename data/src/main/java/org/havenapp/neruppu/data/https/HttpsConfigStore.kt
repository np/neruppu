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

package org.havenapp.neruppu.data.https

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.HttpsConfigRepository
import org.havenapp.neruppu.domain.util.HttpsBodyEncoding
import org.havenapp.neruppu.domain.util.HttpsRequestTemplate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpsConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertTargetRepository: AlertTargetRepository
) : HttpsConfigRepository {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "neruppu_https_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override var endpointUrl: String
        get() = prefs.getString(KEY_ENDPOINT_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ENDPOINT_URL, value.trim()).apply()

    override var bodyEncoding: HttpsBodyEncoding
        get() = prefs.getString(KEY_ENCODING, null)
            ?.let { name -> HttpsBodyEncoding.entries.firstOrNull { it.name == name } }
            ?: HttpsBodyEncoding.MULTIPART
        set(value) = prefs.edit().putString(KEY_ENCODING, value.name).apply()

    override var headers: String
        get() = prefs.getString(KEY_HEADERS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HEADERS, value).apply()

    override var fields: String
        get() = prefs.getString(KEY_FIELDS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FIELDS, value).apply()

    override var fileField: String
        get() = prefs.getString(KEY_FILE_FIELD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FILE_FIELD, value.trim()).apply()

    override val isComplete: Boolean
        get() = template().isSuccess

    /** Parsed, validated request template; failure lists what is wrong. */
    fun template(): Result<HttpsRequestTemplate> =
        HttpsRequestTemplate.parse(endpointUrl, bodyEncoding, headers, fields, fileField)

    override fun clear() {
        prefs.edit().clear().apply()
        alertTargetRepository.clearActiveTarget()
        Log.d("HttpsConfigStore", "HTTPS config cleared, target reset to NONE")
    }

    companion object {
        private const val KEY_ENDPOINT_URL = "endpoint_url"
        private const val KEY_ENCODING = "body_encoding"
        private const val KEY_HEADERS = "headers"
        private const val KEY_FIELDS = "fields"
        private const val KEY_FILE_FIELD = "file_field"
    }
}
