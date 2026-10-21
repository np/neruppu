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

package org.havenapp.neruppu.ui.features.permission

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.havenapp.neruppu.core.ui.theme.NeruppuAmber
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary
import org.havenapp.neruppu.ui.R

/** Camera/microphone denied for good: only the system settings can grant them now. */
@Composable
fun GuardPermissionsDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.NoPhotography, contentDescription = null, tint = NeruppuAmber) },
        title = { Text(stringResource(R.string.permission_denied_title), color = TextPrimary) },
        text = {
            Text(
                stringResource(R.string.permission_denied_message),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeruppuOrange)
            ) { Text(stringResource(R.string.permission_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.permission_not_now), color = TextSecondary) }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}
