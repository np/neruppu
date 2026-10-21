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

package org.havenapp.neruppu.ui.features.remote

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * UI side of the remote's lock. The real protection is the auth-bound
 * Keystore key (data/remote/SecretVault.kt, 5 minutes after an unlock);
 * this only decides when to ask, and asks again whenever the app comes
 * back to the foreground.
 */
object RemoteLock {
    private const val VALIDITY_MS = 4 * 60 * 1000L // a bit under the key's 5 minutes
    @Volatile private var unlockedAt = 0L
    /** True while the prompt (or the PIN screen it opens) is showing. */
    @Volatile var prompting = false
        internal set

    fun markUnlocked() { unlockedAt = SystemClock.elapsedRealtime() }
    fun relock() { unlockedAt = 0L }
    fun recentlyUnlocked(): Boolean =
        unlockedAt != 0L && SystemClock.elapsedRealtime() - unlockedAt < VALIDITY_MS
}

/**
 * Strong biometrics or the device PIN/pattern/password. Before Android 11
 * the strong + credential combination does not exist in BiometricPrompt.
 */
private val AUTHENTICATORS =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

fun interface UnlockRequest {
    /** Runs [onUnlocked] right away if unlocked recently, else after a successful prompt. */
    fun run(onError: (String) -> Unit, onUnlocked: () -> Unit)
}

@Composable
fun rememberRemoteUnlock(): UnlockRequest {
    val context = LocalContext.current
    return remember(context) {
        UnlockRequest { onError, onUnlocked ->
            if (RemoteLock.recentlyUnlocked()) {
                onUnlocked()
                return@UnlockRequest
            }
            val activity = context.findFragmentActivity()
                ?: return@UnlockRequest onError("Unlock is not available here")
            when (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)) {
                BiometricManager.BIOMETRIC_SUCCESS -> Unit
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    return@UnlockRequest onError("Set a screen lock (PIN, pattern or password) to use the remote control")
                else -> return@UnlockRequest onError("This phone cannot verify your identity")
            }
            RemoteLock.prompting = true
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        RemoteLock.prompting = false
                        RemoteLock.markUnlocked()
                        onUnlocked()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        RemoteLock.prompting = false
                        onError(errString.toString())
                    }
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock remote control")
                    .setSubtitle("Its keys open the guarded phone's photos")
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build()
            )
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
