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

package org.havenapp.neruppu.domain.repository

/**
 * Holds the age recipients (X25519 public keys) that captures are encrypted to.
 * The private key never reaches the device.
 */
interface EncryptionKeyRepository {
    /** Newline-separated list of age public keys; empty when unconfigured. */
    val recipients: String
    val isConfigured: Boolean

    /** Returns false (and stores nothing) if any key is not a valid age recipient. */
    fun setRecipients(keys: List<String>): Boolean
    fun clear()
}
