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

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import org.havenapp.neruppu.domain.remote.RemoteKeysLostException
import org.havenapp.neruppu.domain.remote.RemoteLockedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Seals data so it can only be opened shortly after the user unlocked. */
interface SecretVault {
    /** @throws RemoteLockedException if not unlocked in the last [UNLOCK_VALIDITY_SECONDS]. */
    fun seal(plaintext: ByteArray): ByteArray
    /** @throws RemoteLockedException, RemoteKeysLostException */
    fun open(sealed: ByteArray): ByteArray
    fun delete()

    companion object {
        const val UNLOCK_VALIDITY_SECONDS = 300
    }
}

/**
 * AES-256-GCM key in the Android Keystore that requires biometric (strong)
 * or device-credential authentication within the last 5 minutes. Without a
 * recent unlock, even this app cannot read what it sealed. Creating it
 * requires a secure lock screen.
 */
@Singleton
class KeystoreSecretVault @Inject constructor() : SecretVault {

    private val keyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    private fun key(create: Boolean): SecretKey? {
        (keyStore.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        if (!create) return null
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        SecretVault.UNLOCK_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(SecretVault.UNLOCK_VALIDITY_SECONDS)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    override fun seal(plaintext: ByteArray): ByteArray = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray = guarded {
        val key = key(create = false) ?: throw RemoteKeysLostException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_SIZE))
        cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    override fun delete() {
        runCatching { keyStore.deleteEntry(ALIAS) }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: UserNotAuthenticatedException) {
        throw RemoteLockedException()
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw RemoteKeysLostException()
    } catch (e: IllegalStateException) {
        // Raised at key creation when no secure lock screen is set.
        if (e.message?.contains("secure lock screen", ignoreCase = true) == true) {
            throw IllegalStateException("Set a screen lock (PIN, pattern or password) to use this phone as a remote")
        }
        throw e
    } catch (e: java.security.InvalidAlgorithmParameterException) {
        throw IllegalStateException("Set a screen lock (PIN, pattern or password) to use this phone as a remote")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "neruppu_remote_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
