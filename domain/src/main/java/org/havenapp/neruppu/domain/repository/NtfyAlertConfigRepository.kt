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

import java.security.SecureRandom

/**
 * ntfy as an alert channel (https://ntfy.sh): the app builds the requests,
 * the user only picks a server and a topic. On ntfy.sh anyone who knows the
 * topic can read it, so the default topic is long and random.
 */
interface NtfyAlertConfigRepository {
    var server: String
    var topic: String
    /** Optional access token (protected topic or own server). */
    var accessToken: String
    /** Attach the encrypted captures (.age) to the notifications. */
    var attachCaptures: Boolean
    val isComplete: Boolean
    fun clear()

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        private val TOPIC = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun isValidServer(server: String) = server.trim().startsWith("https://") && server.trim().length > "https://".length
        fun isValidTopic(topic: String) = TOPIC.matches(topic.trim())

        /** "neruppu-" + 20 random characters (about 100 bits): not guessable. */
        fun randomTopic(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return "neruppu-" + (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
