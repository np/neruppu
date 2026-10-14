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

import org.havenapp.neruppu.domain.util.HttpsEndpointValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsEndpointValidatorTest {

    @Test
    fun `TC-HTTPS-20 accepts https URLs`() {
        assertTrue(HttpsEndpointValidator.isValid("https://example.org"))
        assertTrue(HttpsEndpointValidator.isValid("https://example.org:8443/hook?k=v"))
        assertTrue(HttpsEndpointValidator.isValid("  HTTPS://Example.org/hook  "))
    }

    @Test
    fun `TC-HTTPS-21 rejects anything that is not a plain https URL`() {
        assertFalse(HttpsEndpointValidator.isValid(""))
        assertFalse(HttpsEndpointValidator.isValid("http://example.org/hook"))
        assertFalse(HttpsEndpointValidator.isValid("example.org/hook"))
        assertFalse(HttpsEndpointValidator.isValid("https://"))
        assertFalse(HttpsEndpointValidator.isValid("https://user:pass@example.org/hook"))
        assertFalse(HttpsEndpointValidator.isValid("https://example.org/hook#frag"))
        assertFalse(HttpsEndpointValidator.isValid("ftp://example.org"))
    }
}
