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

package org.havenapp.neruppu.domain.remote

import kotlinx.coroutines.flow.Flow

/** A phone is either guarded, the remote control of a guarded phone, or neither. */
enum class RemoteRole { NONE, GUARD, CONTROLLER }

enum class RemoteCommand { STATUS, PHOTO }

data class RemoteStatus(
    val monitoring: Boolean,
    val armed: Boolean,
    val armingRemainingSeconds: Int,
    val guardingSince: Long?,
    val batteryPercent: Int,
    val pluggedIn: Boolean,
    val lastEventAt: Long?,
    val lastEventType: String?
)

sealed class RemoteUpdate {
    data class Connected(val connected: Boolean) : RemoteUpdate()
    data class Status(val at: Long, val status: RemoteStatus) : RemoteUpdate()
    class Photo(val at: Long, val jpeg: ByteArray) : RemoteUpdate()
    data class Failure(val at: Long, val message: String) : RemoteUpdate()
}

/**
 * Remote control over ntfy: the controller phone sends end-to-end encrypted,
 * authenticated commands; the guarded phone answers with its status or an
 * age-encrypted photo only the controller can open. See docs/remote-control.md.
 */
interface RemoteControlRepository {
    val role: RemoteRole

    /** Controller: creates keys and a channel; returns the pairing code to show as QR. */
    fun createControllerPairing(server: String): String

    /**
     * Controller: publishes this phone's settings (sensors, delays, alert
     * channel with credentials, age keys) for the phone that pairs next.
     */
    suspend fun publishSettings(): Result<Unit>

    /** Controller: the pairing code again, or null if not a controller. */
    fun pairingCode(): String?

    /**
     * Guarded phone: stores the channel from a scanned code, trusts its age key
     * and applies the remote's settings. True if the settings were applied,
     * false if pairing worked but no settings could be fetched.
     */
    suspend fun pairAsGuard(code: String): Result<Boolean>

    /** Forgets the pairing on this phone (the age key added to the guarded phone stays until removed in Encryption). */
    fun unpair()

    suspend fun send(command: RemoteCommand): Result<Unit>

    /** Controller: answers from the guarded phone, while collected (reconnects by itself). */
    fun updates(): Flow<RemoteUpdate>
}
