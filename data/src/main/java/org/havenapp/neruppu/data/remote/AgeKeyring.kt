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

import neruppuage.Neruppuage
import javax.inject.Inject
import javax.inject.Singleton

/** age operations used by remote control; an interface so the logic is unit-testable. */
interface AgeKeyring {
    fun generateIdentity(): String
    fun recipientOf(identity: String): String
    fun decrypt(ciphertext: ByteArray, identity: String): ByteArray
    fun isValidRecipient(recipient: String): Boolean
}

@Singleton
class GoAgeKeyring @Inject constructor() : AgeKeyring {
    override fun generateIdentity(): String = Neruppuage.generateIdentity()
    override fun recipientOf(identity: String): String = Neruppuage.recipientOf(identity)
    override fun decrypt(ciphertext: ByteArray, identity: String): ByteArray = Neruppuage.decryptBytes(ciphertext, identity)
    override fun isValidRecipient(recipient: String): Boolean = Neruppuage.isValidRecipient(recipient)
}
