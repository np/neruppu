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

package org.havenapp.neruppu.ui.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import org.havenapp.neruppu.domain.util.HttpsBodyEncoding
import org.havenapp.neruppu.domain.util.HttpsPreset
import org.havenapp.neruppu.domain.util.HttpsRequestTemplate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import org.havenapp.neruppu.core.ui.theme.BackgroundPrimary
import org.havenapp.neruppu.core.ui.theme.BackgroundSecondary
import org.havenapp.neruppu.core.ui.theme.BorderTertiary
import org.havenapp.neruppu.core.ui.theme.NeruppuBlue
import org.havenapp.neruppu.core.ui.theme.NeruppuGreen
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.NeruppuRed
import org.havenapp.neruppu.core.ui.theme.NeruppuTheme
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary
import org.havenapp.neruppu.ui.components.ScreenHeader
import org.havenapp.neruppu.ui.features.remote.RemotePairingRow
import org.havenapp.neruppu.ui.features.remote.RemotePairingViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    motionEnabled: Boolean,
    onMotionToggle: (Boolean) -> Unit,
    soundEnabled: Boolean,
    onSoundToggle: (Boolean) -> Unit,
    lightEnabled: Boolean,
    onLightToggle: (Boolean) -> Unit,
    movementEnabled: Boolean = true,
    onMovementToggle: (Boolean) -> Unit = {},
    motionSensitivity: Float,
    onMotionSensitivityChange: (Float) -> Unit,
    soundThreshold: Float,
    onSoundThresholdChange: (Float) -> Unit,
    pushAlerts: Boolean,
    onPushAlertsToggle: (Boolean) -> Unit,
    savePhotos: Boolean,
    onSavePhotosToggle: (Boolean) -> Unit,
    armingDelaySeconds: Int = 60,
    onArmingDelayChange: (Int) -> Unit = {},
    alertGroupWindowSeconds: Int = 120,
    onAlertGroupWindowChange: (Int) -> Unit = {},
    alertHourlyCap: Int = 30,
    onAlertHourlyCapChange: (Int) -> Unit = {},
    telegramViewModel: TelegramSettingsViewModel = hiltViewModel(),
    matrixViewModel: MatrixSettingsViewModel = hiltViewModel(),
    httpsViewModel: HttpsSettingsViewModel = hiltViewModel(),
    ntfyViewModel: NtfySettingsViewModel = hiltViewModel(),
    deletePasswordViewModel: DeletePasswordSettingsViewModel = hiltViewModel(),
    encryptionViewModel: EncryptionSettingsViewModel = hiltViewModel(),
    remotePairingViewModel: RemotePairingViewModel = hiltViewModel()
) {

    val telegramUiState by telegramViewModel.uiState.collectAsState()
    val matrixUiState by matrixViewModel.uiState.collectAsState()
    val httpsUiState by httpsViewModel.uiState.collectAsState()
    val ntfyUiState by ntfyViewModel.uiState.collectAsState()
    val deletePasswordUiState by deletePasswordViewModel.uiState.collectAsState()
    val encryptionUiState by encryptionViewModel.uiState.collectAsState()
    val remotePairingUiState by remotePairingViewModel.uiState.collectAsState()
    // Pairing as guarded phone applies the remote's settings: show them.
    LaunchedEffect(remotePairingUiState.role, remotePairingUiState.settingsVersion) {
        encryptionViewModel.refresh()
        telegramViewModel.refresh()
        matrixViewModel.refresh()
        httpsViewModel.refresh()
        ntfyViewModel.refresh()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    var showTelegramConfig by remember { mutableStateOf(false) }
    var showMatrixConfig by remember { mutableStateOf(false) }
    var showHttpsConfig by remember { mutableStateOf(false) }
    var showNtfyConfig by remember { mutableStateOf(false) }
    var showEncryptionConfig by remember { mutableStateOf(false) }
    var showSetupPasswordDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showRemovePasswordDialog by remember { mutableStateOf(false) }

    var setupNewPassword by remember { mutableStateOf("") }
    var setupConfirmPassword by remember { mutableStateOf("") }
    var changeCurrentPassword by remember { mutableStateOf("") }
    var changeNewPassword by remember { mutableStateOf("") }
    var changeConfirmPassword by remember { mutableStateOf("") }
    var removeCurrentPassword by remember { mutableStateOf("") }

    LaunchedEffect(deletePasswordUiState.successMessage, deletePasswordUiState.errorMessage) {
        val message = deletePasswordUiState.successMessage ?: deletePasswordUiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            duration = SnackbarDuration.Short
        )
        deletePasswordViewModel.clearMessages()
    }

    val telegramFields = listOf(
        IntegrationField(
            label = "Bot Token",
            placeholder = "123456:ABC-DEF...",
            value = telegramUiState.botToken,
            onValueChange = telegramViewModel::onTokenChange,
            isPassword = true
        ),
        IntegrationField(
            label = "Chat ID",
            placeholder = "123456789",
            value = telegramUiState.chatId,
            onValueChange = telegramViewModel::onChatIdChange
        )
    )

    val matrixFields = listOf(
        IntegrationField(
            label = "Homeserver URL",
            placeholder = "https://matrix.org",
            value = matrixUiState.homeserverUrl,
            onValueChange = matrixViewModel::onHomeserverChange
        ),
        IntegrationField(
            label = "Room ID",
            placeholder = "!abc123:matrix.org",
            value = matrixUiState.roomId,
            onValueChange = matrixViewModel::onRoomIdChange
        ),
        IntegrationField(
            label = "Access Token",
            placeholder = "syt_...",
            value = matrixUiState.accessToken,
            onValueChange = matrixViewModel::onTokenChange,
            isPassword = true
        )
    )

    val httpsFields = listOf(
        IntegrationField(
            label = "Endpoint URL",
            placeholder = "https://example.org/neruppu",
            value = httpsUiState.endpointUrl,
            onValueChange = httpsViewModel::onEndpointUrlChange
        ),
        IntegrationField(
            label = "Headers",
            placeholder = "Authorization: Bearer …",
            value = httpsUiState.headers,
            onValueChange = httpsViewModel::onHeadersChange,
            singleLine = false,
            supportingText = "One \"Name: value\" per line"
        ),
        IntegrationField(
            label = "Fields",
            placeholder = "message={message}",
            value = httpsUiState.fields,
            onValueChange = httpsViewModel::onFieldsChange,
            singleLine = false,
            supportingText = "One \"name=value\" per line. Placeholders: " +
                HttpsRequestTemplate.PLACEHOLDERS.keys.joinToString(" ") { "{$it}" }
        ),
        IntegrationField(
            label = "Capture field (multipart only)",
            placeholder = "file",
            value = httpsUiState.fileField,
            onValueChange = httpsViewModel::onFileFieldChange,
            supportingText = "Empty: captures stay on the device, only the notification is sent"
        )
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BackgroundPrimary,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScreenHeader(title = "Settings")

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                SectionTitle("Sensors")
                SettingsList {
                    SettingRow(
                        icon = Icons.Default.CameraAlt,
                        label = "Motion detection",
                        subLabel = "Movement in the camera picture",
                        checked = motionEnabled,
                        onCheckedChange = onMotionToggle
                    )
                    SettingRow(
                        icon = Icons.Default.Mic,
                        label = "Acoustic monitor",
                        subLabel = "Loud noises (microphone)",
                        checked = soundEnabled,
                        onCheckedChange = onSoundToggle
                    )
                    SettingRow(
                        icon = Icons.Default.WbSunny,
                        label = "Luminosity",
                        subLabel = "Ambient light sensor",
                        checked = lightEnabled,
                        onCheckedChange = onLightToggle
                    )
                    SettingRow(
                        icon = Icons.Default.OpenWith,
                        label = "Phone moved",
                        subLabel = "Picked up or shaken (accelerometer)",
                        checked = movementEnabled,
                        onCheckedChange = onMovementToggle
                    )
                }

                SectionTitle("Sensitivity")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BackgroundSecondary, RoundedCornerShape(12.dp))
                        .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    SliderWrap(
                        label = "Motion threshold",
                        value = motionSensitivity,
                        onValueChange = onMotionSensitivityChange,
                        displayValue = if (motionSensitivity < 0.3f) "Low" else if (motionSensitivity < 0.7f) "Medium" else "High",
                        color = NeruppuOrange
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    SliderWrap(
                        label = "Sound threshold",
                        value = soundThreshold,
                        onValueChange = onSoundThresholdChange,
                        displayValue = if (soundThreshold < 0.3f) "Low" else if (soundThreshold < 0.7f) "Medium" else "High",
                        color = NeruppuBlue
                    )
                }

                SectionTitle("Exit delay")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BackgroundSecondary, RoundedCornerShape(12.dp))
                        .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    ArmingDelaySlider(armingDelaySeconds, onArmingDelayChange)
                    Text(
                        "After Start, events are ignored for this long so you can leave the room. " +
                            "A notification tells you when guarding is active.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                SectionTitle("Remote alerts")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BackgroundSecondary, RoundedCornerShape(12.dp))
                        .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    SteppedSlider(
                        "Group follow-up events",
                        GROUP_WINDOW_STEPS,
                        alertGroupWindowSeconds,
                        { if (it == 0) "Off" else "every ${formatArmingDelay(it)}" },
                        onAlertGroupWindowChange
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SteppedSlider(
                        "At most",
                        HOURLY_CAP_STEPS,
                        alertHourlyCap,
                        { if (it == 0) "Unlimited" else "$it alerts / hour" },
                        onAlertHourlyCapChange
                    )
                    Text(
                        "The first event of an incident is sent at once; later ones are summed up " +
                            "in one message per period, with one capture. Other captures stay on the device.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                SectionTitle("Notifications")
                SettingsList {
                    SettingRow(
                        icon = Icons.Default.Notifications,
                        label = "Push alerts",
                        subLabel = "On every event",
                        checked = pushAlerts,
                        onCheckedChange = onPushAlertsToggle
                    )
                    SettingRow(
                        icon = Icons.Default.Photo,
                        label = "Save photos",
                        subLabel = "On motion trigger",
                        checked = savePhotos,
                        onCheckedChange = onSavePhotosToggle
                    )
                }

                SectionTitle("Encryption")
                SettingsList {
                    IntegrationSettingRow(
                        icon = Icons.Default.Lock,
                        label = "Age public key",
                        isConfigured = encryptionUiState.isConfigured,
                        onClick = { showEncryptionConfig = true }
                    )
                }

                SectionTitle("Log deletion")
                DeletePasswordManagementRow(
                    hasPassword = deletePasswordUiState.hasPassword,
                    onSetClick = { showSetupPasswordDialog = true },
                    onChangeClick = { showChangePasswordDialog = true },
                    onRemoveClick = { showRemovePasswordDialog = true }
                )

                SectionTitle("Remote control")
                RemotePairingRow(
                    state = remotePairingUiState,
                    onBecomeRemote = remotePairingViewModel::becomeRemote,
                    onPairAsGuard = remotePairingViewModel::pairAsGuard,
                    onUnpair = remotePairingViewModel::unpair,
                    onSendSettings = remotePairingViewModel::sendSettings,
                    onUnlocked = remotePairingViewModel::onUnlocked,
                    onClearError = remotePairingViewModel::clearError
                )

                SectionTitle("Integrations")
                SettingsList {
                    IntegrationSettingRow(
                        icon = Icons.Default.NotificationsActive,
                        label = "ntfy (simplest)",
                        isConfigured = ntfyUiState.isSaved,
                        onClick = { showNtfyConfig = true }
                    )
                    IntegrationSettingRow(
                        icon = Icons.Default.Send,
                        label = "Telegram Alerts",
                        isConfigured = telegramUiState.isSaved,
                        onClick = { showTelegramConfig = true }
                    )
                    IntegrationSettingRow(
                        icon = Icons.Default.Message,
                        label = "Matrix Alerts",
                        isConfigured = matrixUiState.isSaved,
                        onClick = { showMatrixConfig = true }
                    )
                    IntegrationSettingRow(
                        icon = Icons.Default.Http,
                        label = "HTTPS Webhook",
                        isConfigured = httpsUiState.isSaved,
                        onClick = { showHttpsConfig = true }
                    )
                }
            }
        }
    }

    PasswordManagementDialog(
        title = "Set delete password",
        description = "This password is required every time logs and media are cleared.",
        fields = listOf(
            PasswordField(
                label = "New password",
                value = setupNewPassword,
                onValueChange = { setupNewPassword = it },
                isPassword = true
            ),
            PasswordField(
                label = "Confirm password",
                value = setupConfirmPassword,
                onValueChange = { setupConfirmPassword = it },
                isPassword = true
            )
        ),
        confirmText = "Set password",
        isVisible = showSetupPasswordDialog,
        onConfirm = {
            deletePasswordViewModel.setPassword(setupNewPassword, setupConfirmPassword) {
                showSetupPasswordDialog = false
                setupNewPassword = ""
                setupConfirmPassword = ""
            }
        },
        onDismiss = {
            showSetupPasswordDialog = false
            setupNewPassword = ""
            setupConfirmPassword = ""
        }
    )

    PasswordManagementDialog(
        title = "Change delete password",
        description = "Enter the current password, then set the new password.",
        fields = listOf(
            PasswordField(
                label = "Current password",
                value = changeCurrentPassword,
                onValueChange = { changeCurrentPassword = it },
                isPassword = true
            ),
            PasswordField(
                label = "New password",
                value = changeNewPassword,
                onValueChange = { changeNewPassword = it },
                isPassword = true
            ),
            PasswordField(
                label = "Confirm password",
                value = changeConfirmPassword,
                onValueChange = { changeConfirmPassword = it },
                isPassword = true
            )
        ),
        confirmText = "Change password",
        isVisible = showChangePasswordDialog,
        onConfirm = {
            deletePasswordViewModel.changePassword(changeCurrentPassword, changeNewPassword, changeConfirmPassword) {
                showChangePasswordDialog = false
                changeCurrentPassword = ""
                changeNewPassword = ""
                changeConfirmPassword = ""
            }
        },
        onDismiss = {
            showChangePasswordDialog = false
            changeCurrentPassword = ""
            changeNewPassword = ""
            changeConfirmPassword = ""
        }
    )

    PasswordManagementDialog(
        title = "Remove delete password",
        description = "Removing this password will block log and media deletion until a new password is set.",
        fields = listOf(
            PasswordField(
                label = "Current password",
                value = removeCurrentPassword,
                onValueChange = { removeCurrentPassword = it },
                isPassword = true
            )
        ),
        confirmText = "Remove password",
        isVisible = showRemovePasswordDialog,
        onConfirm = {
            deletePasswordViewModel.removePassword(removeCurrentPassword) {
                showRemovePasswordDialog = false
                removeCurrentPassword = ""
            }
        },
        onDismiss = {
            showRemovePasswordDialog = false
            removeCurrentPassword = ""
        }
    )

    EncryptionKeyDialog(
        isVisible = showEncryptionConfig,
        uiState = encryptionUiState,
        onValueChange = encryptionViewModel::onRecipientsChange,
        onSave = { if (encryptionViewModel.save()) showEncryptionConfig = false },
        onClear = {
            encryptionViewModel.clear()
            showEncryptionConfig = false
        },
        onDismiss = {
            encryptionViewModel.reset()
            showEncryptionConfig = false
        }
    )

    IntegrationConfigPopup(
        integrationType = IntegrationType.TELEGRAM,
        isVisible = showTelegramConfig,
        onDismiss = { showTelegramConfig = false },
        uiState = telegramUiState,
        actions = telegramViewModel,
        fields = telegramFields,
        descriptionText = "Provide Telegram bot credentials to receive real-time alerts. Use @BotFather to create a bot."
    )

    IntegrationConfigPopup(
        integrationType = IntegrationType.MATRIX,
        isVisible = showMatrixConfig,
        onDismiss = { showMatrixConfig = false },
        uiState = matrixUiState,
        actions = matrixViewModel,
        fields = matrixFields,
        descriptionText = "Provide Matrix credentials to receive real-time alerts on other devices."
    )

    IntegrationConfigPopup(
        integrationType = IntegrationType.NTFY,
        isVisible = showNtfyConfig,
        onDismiss = { showNtfyConfig = false },
        uiState = ntfyUiState,
        actions = ntfyViewModel,
        fields = listOf(
            IntegrationField(
                label = "Server",
                placeholder = "https://ntfy.sh",
                value = ntfyUiState.server,
                onValueChange = ntfyViewModel::onServerChange
            ),
            IntegrationField(
                label = "Topic",
                placeholder = "neruppu-…",
                value = ntfyUiState.topic,
                onValueChange = ntfyViewModel::onTopicChange,
                supportingText = "Anyone who knows it can read your alerts: keep the random one"
            ),
            IntegrationField(
                label = "Access token — leave empty unless your topic is reserved",
                placeholder = "tk_…",
                value = ntfyUiState.accessToken,
                onValueChange = ntfyViewModel::onAccessTokenChange,
                isPassword = true,
                supportingText = "Only needed if you reserved this topic with an ntfy.sh account, or if your own server requires login. Create the token in your ntfy account (tk_…)."
            )
        ),
        descriptionText = "Install the ntfy app on the phone that should receive alerts, then Save and Test.",
        extraContent = {
            NtfySetupHelp(
                server = ntfyUiState.server,
                topic = ntfyUiState.topic,
                attachCaptures = ntfyUiState.attachCaptures,
                onNewTopic = ntfyViewModel::newTopic,
                onAttachCapturesChange = ntfyViewModel::onAttachCapturesChange
            )
        }
    )

    IntegrationConfigPopup(
        integrationType = IntegrationType.HTTPS,
        isVisible = showHttpsConfig,
        onDismiss = { showHttpsConfig = false },
        uiState = httpsUiState,
        actions = httpsViewModel,
        fields = httpsFields,
        descriptionText = "Each alert is POSTed to your HTTPS endpoint as you define it here. Captures are attached as the encrypted .age file.",
        extraContent = {
            HttpsRequestChoices(
                encoding = httpsUiState.encoding,
                onEncodingChange = httpsViewModel::onEncodingChange,
                onPreset = httpsViewModel::applyPreset
            )
        }
    )
}

@Composable
fun NtfySetupHelp(
    server: String,
    topic: String,
    attachCaptures: Boolean,
    onNewTopic: () -> Unit,
    onAttachCapturesChange: (Boolean) -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "In the ntfy app: + → Subscribe to topic → \"$topic\"" +
                if (server.trimEnd('/') != "https://ntfy.sh") " (use another server: $server)" else "",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(topic)) }) {
                Text("Copy topic", color = NeruppuOrange)
            }
            TextButton(onClick = onNewTopic) { Text("New random topic", color = NeruppuOrange) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = attachCaptures, onCheckedChange = onAttachCapturesChange)
            Text(
                "Attach captures (encrypted .age files; otherwise they stay on this phone)",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HttpsRequestChoices(
    encoding: HttpsBodyEncoding,
    onEncodingChange: (HttpsBodyEncoding) -> Unit,
    onPreset: (HttpsPreset) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Start from", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HttpsPreset.entries.forEach { preset ->
                AssistChip(onClick = { onPreset(preset) }, label = { Text(preset.label) })
            }
        }
        Text("Body encoding", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HttpsBodyEncoding.entries.forEach { option ->
                FilterChip(
                    selected = option == encoding,
                    onClick = { onEncodingChange(option) },
                    label = {
                        Text(
                            when (option) {
                                HttpsBodyEncoding.MULTIPART -> "multipart"
                                HttpsBodyEncoding.FORM -> "form"
                                HttpsBodyEncoding.JSON -> "JSON"
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun EncryptionKeyDialog(
    isVisible: Boolean,
    uiState: EncryptionUiState,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Age public key",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Photos and audio are encrypted to this key at capture time. " +
                        "Generate it on another device with age-keygen and paste the age1… public key. " +
                        "One key per line. Monitoring cannot start without one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = uiState.recipientsInput,
                    onValueChange = onValueChange,
                    label = { Text("Recipients", color = TextSecondary) },
                    placeholder = { Text("age1…", color = TextSecondary) },
                    isError = uiState.error != null,
                    supportingText = if (uiState.error != null) {
                        { Text(uiState.error.orEmpty(), color = NeruppuRed) }
                    } else null,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = NeruppuOrange,
                        focusedBorderColor = NeruppuOrange,
                        unfocusedBorderColor = BorderTertiary,
                        unfocusedContainerColor = Color.White,
                        focusedContainerColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = uiState.recipientsInput.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeruppuOrange)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (uiState.isConfigured) {
                    TextButton(onClick = onClear) {
                        Text("Remove", color = NeruppuRed)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun DeletePasswordManagementRow(
    hasPassword: Boolean,
    onSetClick: () -> Unit,
    onChangeClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = if (hasPassword) NeruppuGreen else TextSecondary,
            modifier = Modifier.size(24.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Delete password",
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                if (hasPassword) "Required before clearing events and media" else "Set a password to enable clearing",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onSetClick,
            enabled = !hasPassword,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeruppuOrange)
        ) {
            Text("Set", fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onChangeClick,
            enabled = hasPassword,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeruppuBlue)
        ) {
            Text("Change", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onRemoveClick,
            enabled = hasPassword,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeruppuRed),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (hasPassword) NeruppuRed else BorderTertiary)
        ) {
            Text("Remove", fontWeight = FontWeight.SemiBold)
        }
    }
}

data class PasswordField(
    val label: String,
    val value: String,
    val onValueChange: (String) -> Unit,
    val isPassword: Boolean = true
)

@Composable
fun PasswordManagementDialog(
    title: String,
    description: String,
    fields: List<PasswordField>,
    confirmText: String,
    isVisible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                fields.forEach { field ->
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = field.onValueChange,
                        label = { Text(field.label, color = TextSecondary) },
                        singleLine = true,
                        visualTransformation = if (field.isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = NeruppuOrange,
                            focusedBorderColor = NeruppuOrange,
                            unfocusedBorderColor = BorderTertiary,
                            unfocusedContainerColor = Color.White,
                            focusedContainerColor = Color.White
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = fields.all { it.value.isNotBlank() },
                shape = RoundedCornerShape(8.dp),
                colors = if (confirmText == "Remove password") {
                    ButtonDefaults.buttonColors(containerColor = NeruppuRed)
                } else {
                    ButtonDefaults.buttonColors(containerColor = NeruppuOrange)
                }
            ) {
                Text(confirmText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun IntegrationSettingRow(
    icon: ImageVector,
    label: String,
    isConfigured: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isConfigured) NeruppuGreen else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                if (isConfigured) "Configured" else "Not configured",
                color = if (isConfigured) NeruppuGreen else TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsList(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
fun SettingRow(
    icon: ImageVector,
    label: String,
    subLabel: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subLabel, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NeruppuOrange,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = BackgroundSecondary,
                uncheckedBorderColor = BorderTertiary
            ),
            modifier = Modifier.scale(0.8f)
        )
    }
}

private val ARMING_DELAY_STEPS = listOf(0, 15, 30, 60, 120, 300, 600)

fun formatArmingDelay(seconds: Int): String = when {
    seconds == 0 -> "None"
    seconds < 60 -> "${seconds} s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60} s"
}

@Composable
fun ArmingDelaySlider(seconds: Int, onChange: (Int) -> Unit) =
    SteppedSlider("Wait before guarding", ARMING_DELAY_STEPS, seconds, ::formatArmingDelay, onChange)

/** Slider over a fixed list of values; a stored value between steps snaps to the nearest. */
@Composable
fun SteppedSlider(
    label: String,
    steps: List<Int>,
    value: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit
) {
    val index = steps.indices.minByOrNull { kotlin.math.abs(steps[it] - value) } ?: 0
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Text(
                format(steps[index]),
                color = NeruppuOrange,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
        androidx.compose.material3.Slider(
            value = index.toFloat(),
            onValueChange = { onChange(steps[it.roundToInt().coerceIn(steps.indices)]) },
            valueRange = 0f..(steps.size - 1).toFloat(),
            steps = steps.size - 2,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = NeruppuOrange,
                inactiveTrackColor = BorderTertiary
            )
        )
    }
}

private val GROUP_WINDOW_STEPS = listOf(0, 60, 120, 300, 600)
private val HOURLY_CAP_STEPS = listOf(10, 20, 30, 60, 0)

@Composable
fun SliderWrap(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    displayValue: String,
    color: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Text(displayValue, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = color,
                inactiveTrackColor = BorderTertiary
            ),
            modifier = Modifier.height(24.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    NeruppuTheme {
        SettingsScreen(
            motionEnabled = true,
            onMotionToggle = {},
            soundEnabled = true,
            onSoundToggle = {},
            lightEnabled = false,
            onLightToggle = {},
            motionSensitivity = 0.5f,
            onMotionSensitivityChange = {},
            soundThreshold = 0.6f,
            onSoundThresholdChange = {},
            pushAlerts = true,
            onPushAlertsToggle = {},
            savePhotos = true,
            onSavePhotosToggle = {}
        )
    }
}
