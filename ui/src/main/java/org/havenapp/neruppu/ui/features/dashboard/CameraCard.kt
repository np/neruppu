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

package org.havenapp.neruppu.ui.features.dashboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.havenapp.neruppu.core.ui.theme.BackgroundSecondary
import org.havenapp.neruppu.core.ui.theme.BorderTertiary
import org.havenapp.neruppu.core.ui.theme.NeruppuAmber
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary
import org.havenapp.neruppu.ui.R

/**
 * Which camera guards, and a way to see what it sees. The choice is locked
 * while guarding; the test photo stays on this screen (never stored or sent).
 */
@Composable
fun CameraCard(
    frontCamera: Boolean,
    isMonitoring: Boolean,
    cameraBlack: Boolean,
    canTest: Boolean,
    onSelectCamera: (front: Boolean) -> Unit,
    onTestPhoto: (onResult: (ByteArray?) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var testing by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<ByteArray?>(null) }
    var failed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundSecondary.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (frontCamera) Icons.Default.CameraFront else Icons.Default.CameraRear,
                contentDescription = null,
                tint = NeruppuOrange,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (frontCamera) R.string.camera_front_in_use else R.string.camera_back_in_use),
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (canTest) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = NeruppuOrange, strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = {
                        testing = true
                        failed = false
                        onTestPhoto { jpeg ->
                            testing = false
                            if (jpeg != null) shown = jpeg else failed = true
                        }
                    }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = NeruppuOrange, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.camera_test_photo), color = NeruppuOrange)
                    }
                }
            }
        }

        if (!isMonitoring) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !frontCamera,
                    onClick = { onSelectCamera(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    colors = segmentColors()
                ) { Text(stringResource(R.string.camera_back)) }
                SegmentedButton(
                    selected = frontCamera,
                    onClick = { onSelectCamera(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    colors = segmentColors()
                ) { Text(stringResource(R.string.camera_front)) }
            }
            Text(
                stringResource(if (frontCamera) R.string.camera_front_hint else R.string.camera_back_hint),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (cameraBlack) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.VideocamOff, contentDescription = null, tint = NeruppuAmber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.start_warning_camera_black_body),
                    color = NeruppuAmber,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (failed) {
            Text(stringResource(R.string.camera_test_failed), color = NeruppuAmber, style = MaterialTheme.typography.bodySmall)
        }
    }

    shown?.let { jpeg ->
        val bitmap = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
        AlertDialog(
            onDismissRequest = { shown = null },
            title = {
                Text(
                    stringResource(if (frontCamera) R.string.camera_front_in_use else R.string.camera_back_in_use),
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.camera_test_photo),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp))
                        )
                    }
                    Text(stringResource(R.string.camera_test_not_saved), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text(stringResource(R.string.camera_test_close), color = NeruppuOrange) } },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun segmentColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = NeruppuOrange.copy(alpha = 0.12f),
    activeContentColor = NeruppuOrange,
    activeBorderColor = NeruppuOrange,
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = TextSecondary,
    inactiveBorderColor = BorderTertiary
)
