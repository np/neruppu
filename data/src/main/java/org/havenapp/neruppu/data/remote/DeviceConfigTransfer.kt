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
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.EncryptionKeyRepository
import org.havenapp.neruppu.domain.repository.HttpsConfigRepository
import org.havenapp.neruppu.domain.repository.MatrixConfigRepository
import org.havenapp.neruppu.domain.repository.NtfyAlertConfigRepository
import org.havenapp.neruppu.domain.repository.TelegramConfigRepository
import org.havenapp.neruppu.domain.util.HttpsBodyEncoding
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings a remote hands to the phone it pairs with: sensors, delays,
 * alert grouping, the active alert channel with its credentials, and extra
 * age keys. Sent once, over the encrypted pairing channel.
 */
@Serializable
data class DeviceConfig(
    val v: Int = 1,
    val ts: Long,
    val booleans: Map<String, Boolean> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val alertTarget: String = AlertTarget.NONE.name,
    val telegram: Telegram? = null,
    val matrix: Matrix? = null,
    val https: Https? = null,
    val ntfy: Ntfy? = null,
    /** Encryption keys of the remote, besides its own pairing key. */
    val ageRecipients: List<String> = emptyList()
) {
    @Serializable
    data class Telegram(val botToken: String, val chatId: String)

    @Serializable
    data class Matrix(val homeserverUrl: String, val roomId: String, val accessToken: String)

    @Serializable
    data class Ntfy(val server: String, val topic: String, val accessToken: String, val attachCaptures: Boolean)

    @Serializable
    data class Https(
        val endpointUrl: String,
        val encoding: String,
        val headers: String,
        val fields: String,
        val fileField: String
    )
}

interface DeviceConfigSource {
    fun collect(now: Long): DeviceConfig
    /** Replaces this phone's settings and alert channel; adds the age keys. */
    fun apply(config: DeviceConfig)
}

@Singleton
class DeviceConfigTransfer(
    private val prefs: () -> SharedPreferences,
    private val telegram: TelegramConfigRepository,
    private val matrix: MatrixConfigRepository,
    private val https: HttpsConfigRepository,
    private val ntfy: NtfyAlertConfigRepository,
    private val alertTarget: AlertTargetRepository,
    private val encryptionKeys: EncryptionKeyRepository,
    private val isValidRecipient: (String) -> Boolean
) : DeviceConfigSource {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        telegram: TelegramConfigRepository,
        matrix: MatrixConfigRepository,
        https: HttpsConfigRepository,
        ntfy: NtfyAlertConfigRepository,
        alertTarget: AlertTargetRepository,
        encryptionKeys: EncryptionKeyRepository,
        keyring: AgeKeyring
    ) : this(
        { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) },
        telegram, matrix, https, ntfy, alertTarget, encryptionKeys, keyring::isValidRecipient
    )

    override fun collect(now: Long): DeviceConfig {
        val p = prefs()
        val target = alertTarget.activeTarget
        return DeviceConfig(
            ts = now,
            booleans = BOOLEAN_KEYS.filter(p::contains).associateWith { p.getBoolean(it, false) },
            floats = FLOAT_KEYS.filter(p::contains).associateWith { p.getFloat(it, 0f) },
            ints = INT_KEYS.filter(p::contains).associateWith { p.getInt(it, 0) },
            alertTarget = target.name,
            telegram = if (target == AlertTarget.TELEGRAM) DeviceConfig.Telegram(telegram.botToken, telegram.chatId) else null,
            matrix = if (target == AlertTarget.MATRIX) {
                DeviceConfig.Matrix(matrix.homeserverUrl, matrix.roomId, matrix.accessToken)
            } else null,
            https = if (target == AlertTarget.HTTPS) {
                DeviceConfig.Https(https.endpointUrl, https.bodyEncoding.name, https.headers, https.fields, https.fileField)
            } else null,
            ntfy = if (target == AlertTarget.NTFY) {
                DeviceConfig.Ntfy(ntfy.server, ntfy.topic, ntfy.accessToken, ntfy.attachCaptures)
            } else null,
            ageRecipients = encryptionKeys.recipients.lines().map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    override fun apply(config: DeviceConfig) {
        prefs().edit().apply {
            config.booleans.filterKeys { it in BOOLEAN_KEYS }.forEach { (k, v) -> putBoolean(k, v) }
            config.floats.filterKeys { it in FLOAT_KEYS }.forEach { (k, v) -> putFloat(k, v) }
            config.ints.filterKeys { it in INT_KEYS }.forEach { (k, v) -> putInt(k, v) }
        }.commit()

        // One channel at a time, as when saving it in Settings.
        telegram.clear()
        matrix.clear()
        https.clear()
        ntfy.clear()
        val target = when (config.alertTarget) {
            AlertTarget.TELEGRAM.name -> config.telegram?.let {
                telegram.botToken = it.botToken
                telegram.chatId = it.chatId
                AlertTarget.TELEGRAM.takeIf { telegram.isComplete }
            }
            AlertTarget.MATRIX.name -> config.matrix?.let {
                matrix.homeserverUrl = it.homeserverUrl
                matrix.roomId = it.roomId
                matrix.accessToken = it.accessToken
                AlertTarget.MATRIX.takeIf { matrix.isComplete }
            }
            AlertTarget.HTTPS.name -> config.https?.let {
                https.endpointUrl = it.endpointUrl
                https.bodyEncoding = HttpsBodyEncoding.entries.firstOrNull { e -> e.name == it.encoding }
                    ?: HttpsBodyEncoding.MULTIPART
                https.headers = it.headers
                https.fields = it.fields
                https.fileField = it.fileField
                AlertTarget.HTTPS.takeIf { https.isComplete }
            }
            AlertTarget.NTFY.name -> config.ntfy?.let {
                ntfy.server = it.server
                ntfy.topic = it.topic
                ntfy.accessToken = it.accessToken
                ntfy.attachCaptures = it.attachCaptures
                AlertTarget.NTFY.takeIf { ntfy.isComplete }
            }
            else -> null
        }
        if (target != null) alertTarget.setActiveTarget(target) else alertTarget.clearActiveTarget()

        val merged = (encryptionKeys.recipients.lines() + config.ageRecipients)
            .map { it.trim() }
            .filter { it.isNotEmpty() && isValidRecipient(it) }
            .distinct()
        if (merged.isNotEmpty()) encryptionKeys.setRecipients(merged)
    }

    companion object {
        const val PREFS = "neruppu_prefs"

        // Only these keys travel; anything else in a received config is ignored.
        val BOOLEAN_KEYS = setOf(
            "motion_enabled", "sound_enabled", "light_enabled", "accelerometer_enabled",
            "push_alerts", "save_photos", "use_front_camera"
        )
        val FLOAT_KEYS = setOf("motion_sensitivity", "sound_threshold", "audio_threshold", "audio_capture_duration")
        val INT_KEYS = setOf("arming_delay_seconds", "alert_group_window_seconds", "alert_hourly_cap")
    }
}
