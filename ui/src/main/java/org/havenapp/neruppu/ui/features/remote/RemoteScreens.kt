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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.havenapp.neruppu.core.ui.theme.BackgroundPrimary
import org.havenapp.neruppu.core.ui.theme.BackgroundSecondary
import org.havenapp.neruppu.core.ui.theme.BorderTertiary
import org.havenapp.neruppu.core.ui.theme.NeruppuAmber
import org.havenapp.neruppu.core.ui.theme.NeruppuGreen
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.NeruppuRed
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary
import org.havenapp.neruppu.domain.remote.RemoteCommand
import org.havenapp.neruppu.domain.remote.RemoteRole
import org.havenapp.neruppu.domain.remote.RemoteStatus
import org.havenapp.neruppu.ui.components.ScreenHeader
import java.text.DateFormat
import java.util.Date

const val DEFAULT_NTFY_SERVER = "https://ntfy.sh"

fun qrImage(text: String, size: Int = 640): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Settings row + dialog to pair this phone, in either role. */
@Composable
fun RemotePairingRow(
    state: RemotePairingUiState,
    onBecomeRemote: (server: String) -> Unit,
    onPairAsGuard: (code: String) -> Unit,
    onUnpair: () -> Unit,
    onClearError: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.SettingsRemote,
            contentDescription = null,
            tint = if (state.role == RemoteRole.NONE) TextSecondary else NeruppuGreen,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Remote control", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                when (state.role) {
                    RemoteRole.NONE -> "Not paired"
                    RemoteRole.GUARD -> "This phone is guarded and answers its remote"
                    RemoteRole.CONTROLLER -> "This phone is a remote control"
                },
                color = if (state.role == RemoteRole.NONE) TextSecondary else NeruppuGreen,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
    }

    if (open) {
        RemotePairingDialog(
            state = state,
            onBecomeRemote = onBecomeRemote,
            onPairAsGuard = onPairAsGuard,
            onUnpair = onUnpair,
            onDismiss = {
                open = false
                onClearError()
            }
        )
    }
}

@Composable
private fun RemotePairingDialog(
    state: RemotePairingUiState,
    onBecomeRemote: (String) -> Unit,
    onPairAsGuard: (String) -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit
) {
    var server by remember { mutableStateOf(DEFAULT_NTFY_SERVER) }
    var pasted by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onPairAsGuard)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remote control", style = MaterialTheme.typography.headlineSmall, color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state.role) {
                    RemoteRole.NONE -> {
                        Text(
                            "Pair two phones: the remote gets the guarded phone's status and photos on demand, " +
                                "end-to-end encrypted through ntfy. It cannot stop guarding.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text("On the remote phone", color = TextPrimary, fontWeight = FontWeight.Medium)
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("ntfy server", color = TextSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors()
                        )
                        Button(
                            onClick = { onBecomeRemote(server) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = NeruppuOrange)
                        ) { Text("Use this phone as the remote") }

                        Text("On the guarded phone", color = TextPrimary, fontWeight = FontWeight.Medium)
                        Button(
                            onClick = {
                                scanner.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Scan the code shown on the remote phone")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(false)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = NeruppuOrange)
                        ) { Text("Scan the remote's code") }
                        OutlinedTextField(
                            value = pasted,
                            onValueChange = { pasted = it },
                            label = { Text("…or paste the code", color = TextSecondary) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = fieldColors()
                        )
                        OutlinedButton(
                            onClick = { onPairAsGuard(pasted) },
                            enabled = pasted.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Pair with pasted code") }
                    }

                    RemoteRole.CONTROLLER -> {
                        Text(
                            "On the guarded phone: Settings → Remote control → Scan. " +
                                "Anyone who sees this code can control the guarded phone: show it only while pairing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        state.pairingCode?.let { code ->
                            Image(
                                bitmap = remember(code) { qrImage(code) },
                                contentDescription = "Pairing QR code",
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                            )
                            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                                Text("Copy the code instead", color = NeruppuOrange)
                            }
                        }
                    }

                    RemoteRole.GUARD -> Text(
                        "Paired. While guarding, this phone answers status and photo requests from its remote. " +
                            "The remote's age key was added in Settings → Encryption.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                state.error?.let { Text(it, color = NeruppuRed, style = MaterialTheme.typography.bodySmall) }

                if (state.role != RemoteRole.NONE) {
                    TextButton(onClick = onUnpair) { Text("Unpair", color = NeruppuRed) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) } },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = NeruppuOrange,
    focusedBorderColor = NeruppuOrange,
    unfocusedBorderColor = BorderTertiary
)

/** The remote's main screen: status of the guarded phone and photo on demand. */
@Composable
fun RemoteControlScreen(viewModel: RemoteControlViewModel) {
    val state by viewModel.uiState.collectAsState()
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    Column(Modifier.fillMaxSize().background(BackgroundPrimary)) {
        ScreenHeader(title = "Remote")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (state.connected) NeruppuGreen else NeruppuAmber, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.connected) "Connected to ntfy" else "Reconnecting…",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            StatusCard(state.status, state.statusAt)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommandButton(
                    label = "Status",
                    icon = Icons.Default.Refresh,
                    waiting = state.waitingFor == RemoteCommand.STATUS,
                    modifier = Modifier.weight(1f)
                ) { viewModel.send(RemoteCommand.STATUS) }
                CommandButton(
                    label = "Take photo",
                    icon = Icons.Default.CameraAlt,
                    waiting = state.waitingFor == RemoteCommand.PHOTO,
                    modifier = Modifier.weight(1f)
                ) { viewModel.send(RemoteCommand.PHOTO) }
            }

            state.error?.let { Text(it, color = NeruppuRed, style = MaterialTheme.typography.bodySmall) }

            val photo = state.photo
            if (photo != null) {
                val bitmap = remember(photo) { BitmapFactory.decodeByteArray(photo, 0, photo.size)?.asImageBitmap() }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Photo from the guarded phone",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp))
                    )
                }
                state.photoAt?.let {
                    Text("Taken ${formatTime(it)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CommandButton(
    label: String,
    icon: ImageVector,
    waiting: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !waiting,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NeruppuOrange)
    ) {
        if (waiting) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun StatusCard(status: RemoteStatus?, at: Long?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (status == null) {
            Text("Waiting for the guarded phone…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text("It answers only while guarding.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val (label, color) = when {
            status.armed -> "Guarding" to NeruppuGreen
            status.monitoring -> "Arming (${status.armingRemainingSeconds} s left)" to NeruppuAmber
            else -> "Idle" to TextSecondary
        }
        Text(label, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        status.guardingSince?.let {
            Text("Since ${formatTime(it)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        val battery = if (status.batteryPercent >= 0) "${status.batteryPercent} %" else "?"
        Text(
            "Battery $battery" + if (status.pluggedIn) " · plugged in" else " · on battery",
            color = if (!status.pluggedIn && status.batteryPercent in 0..20) NeruppuRed else TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            status.lastEventAt?.let { "Last event: ${status.lastEventType ?: "?"} at ${formatTime(it)}" }
                ?: "No event since start",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        at?.let { Text("Updated ${formatTime(it)}", color = TextSecondary, style = MaterialTheme.typography.labelSmall) }
    }
}

private fun formatTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ms))
