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

package org.havenapp.neruppu

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import org.havenapp.neruppu.core.ui.theme.BackgroundSecondary
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.NeruppuTheme
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary
import org.havenapp.neruppu.data.local.SharedPrefsAlertGroupingStore
import org.havenapp.neruppu.service.MonitoringService
import org.havenapp.neruppu.ui.features.dashboard.DashboardScreen
import org.havenapp.neruppu.ui.features.logs.LogsScreen
import org.havenapp.neruppu.ui.features.logs.LogsViewModel
import org.havenapp.neruppu.ui.features.permission.PermissionScreen
import org.havenapp.neruppu.ui.features.settings.EncryptionSettingsViewModel
import org.havenapp.neruppu.ui.features.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val monitoringService = mutableStateOf<MonitoringService?>(null)
    private var isBound = false
    private var hasPermissions by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
            val binder = service as MonitoringService.LocalBinder
            monitoringService.value = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName?) {
            monitoringService.value = null
            isBound = false
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isBound) {
                    bindService(Intent(this@MainActivity, MonitoringService::class.java), this, BIND_AUTO_CREATE)
                }
            }, 2000)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        hasPermissions = allGranted
        if (allGranted) {
            bindMonitoringService()
        }
    }

    private fun bindMonitoringService() {
        if (!isBound) {
            bindService(Intent(this, MonitoringService::class.java), connection, BIND_AUTO_CREATE)
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        if (!hasAllPermissions()) {
            requestPermissionLauncher.launch(getRequiredPermissions())
        } else {
            hasPermissions = true
            bindMonitoringService()
        }
    }

    override fun onResume() {
        super.onResume()
        val currentlyHasPermissions = hasAllPermissions()
        if (hasPermissions != currentlyHasPermissions) {
            hasPermissions = currentlyHasPermissions
            if (hasPermissions) {
                bindMonitoringService()
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !hasPermissions }
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        setContent {
            NeruppuTheme {
                if (!hasPermissions) {
                    PermissionScreen(
                        onPermissionsGranted = {
                            hasPermissions = true
                            bindMonitoringService()
                        },
                        onOpenSettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null)
                                )
                            )
                        }
                    )
                } else {
                    MainAppScreen()
                }
            }
        }
    }

    @Composable
    private fun MainAppScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }
        val logsViewModel: LogsViewModel = hiltViewModel()
        // Same activity-scoped instance that SettingsScreen edits, so the
        // dashboard reacts as soon as a key is saved.
        val encryptionViewModel: EncryptionSettingsViewModel = hiltViewModel()
        val encryptionUiState by encryptionViewModel.uiState.collectAsState()

        val context = LocalContext.current
        val prefs = context.getSharedPreferences("neruppu_prefs", MODE_PRIVATE)

        var motionEnabled by remember { mutableStateOf(prefs.getBoolean("motion_enabled", true)) }
        var soundEnabled by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
        var lightEnabled by remember { mutableStateOf(prefs.getBoolean("light_enabled", false)) }
        var motionSensitivity by remember { mutableStateOf(prefs.getFloat("motion_sensitivity", 0.5f)) }
        var soundThreshold by remember { mutableStateOf(prefs.getFloat("sound_threshold", 0.6f)) }
        var pushAlerts by remember { mutableStateOf(prefs.getBoolean("push_alerts", true)) }
        var savePhotos by remember { mutableStateOf(prefs.getBoolean("save_photos", true)) }
        var useFrontCamera by remember { mutableStateOf(prefs.getBoolean("use_front_camera", false)) }
        var alertGroupWindowSeconds by remember {
            mutableIntStateOf(prefs.getInt(
                SharedPrefsAlertGroupingStore.PREF_WINDOW_SECONDS,
                SharedPrefsAlertGroupingStore.DEFAULT_WINDOW_SECONDS
            ))
        }
        var alertHourlyCap by remember {
            mutableIntStateOf(prefs.getInt(
                SharedPrefsAlertGroupingStore.PREF_HOURLY_CAP,
                SharedPrefsAlertGroupingStore.DEFAULT_HOURLY_CAP
            ))
        }
        var armingDelaySeconds by remember {
            mutableIntStateOf(prefs.getInt(
                MonitoringService.PREF_ARMING_DELAY_SECONDS,
                MonitoringService.DEFAULT_ARMING_DELAY_SECONDS
            ))
        }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = BackgroundSecondary,
                    modifier = Modifier.height(80.dp),
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_home), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = NeruppuOrange.copy(alpha = 0.1f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_events), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = NeruppuOrange.copy(alpha = 0.1f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = NeruppuOrange.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                when (selectedTab) {
                    0 -> {
                        val service = monitoringService.value
                        if (service != null) {
                            val isMonitoring by service.isMonitoring.collectAsState()
                            val motionLevel by service.motionLevel.collectAsState()
                            val audioLevel by service.audioLevel.collectAsState()
                            val lightLevel by service.lightLevel.collectAsState()
                            val sessionStartTime by service.sessionStartTime.collectAsState()
                            val lastGuardedTime by service.lastGuardedTime.collectAsState()
                            val armedAtElapsed by service.armedAtElapsed.collectAsState()

                            DashboardScreen(
                                isMonitoring = isMonitoring,
                                motionLevel = motionLevel,
                                audioLevel = audioLevel,
                                lightLevel = lightLevel,
                                accelerometerStable = true,
                                    onToggleMonitoring = {
                                        if (!isMonitoring) {
                                            val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                            if (!hasCamera || !hasAudio) {
                                                hasPermissions = false
                                                return@DashboardScreen
                                            }
                                            // Checked before startForegroundService(): a refused start
                                            // would never reach startForeground() and crash the service.
                                            if (!encryptionUiState.isConfigured) {
                                                return@DashboardScreen
                                            }
                                            val intent = Intent(context, MonitoringService::class.java)
                                            context.startForegroundService(intent)
                                        }
                                        service.toggleMonitoring()
                                    },
                                useFrontCamera = useFrontCamera,
                                encryptionConfigured = encryptionUiState.isConfigured,
                                armedAtElapsed = armedAtElapsed,
                                sessionStartTime = sessionStartTime,
                                lastGuardedTime = lastGuardedTime
                            )

                            val lifecycleOwner = LocalLifecycleOwner.current
                            DisposableEffect(lifecycleOwner) {
                                service.setUiActive(true)
                                onDispose {
                                    service.setUiActive(false)
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = NeruppuOrange)
                            }
                        }
                    }
                    1 -> {
                        LogsScreen(
                            viewModel = logsViewModel,
                            onRequestDelete = { logsViewModel.requestDelete() }
                        )
                    }
                    2 -> {
                        SettingsScreen(
                            motionEnabled = motionEnabled,
                            onMotionToggle = {
                                motionEnabled = it
                                prefs.edit().putBoolean("motion_enabled", it).apply()
                            },
                            soundEnabled = soundEnabled,
                            onSoundToggle = {
                                soundEnabled = it
                                prefs.edit().putBoolean("sound_enabled", it).apply()
                            },
                            lightEnabled = lightEnabled,
                            onLightToggle = {
                                lightEnabled = it
                                prefs.edit().putBoolean("light_enabled", it).apply()
                            },
                            motionSensitivity = motionSensitivity,
                            onMotionSensitivityChange = {
                                motionSensitivity = it
                                prefs.edit().putFloat("motion_sensitivity", it).apply()
                            },
                            soundThreshold = soundThreshold,
                            onSoundThresholdChange = {
                                soundThreshold = it
                                prefs.edit().putFloat("sound_threshold", it).apply()
                            },
                            pushAlerts = pushAlerts,
                            onPushAlertsToggle = {
                                pushAlerts = it
                                prefs.edit().putBoolean("push_alerts", it).apply()
                            },
                            savePhotos = savePhotos,
                            onSavePhotosToggle = {
                                savePhotos = it
                                prefs.edit().putBoolean("save_photos", it).apply()
                            },
                            armingDelaySeconds = armingDelaySeconds,
                            onArmingDelayChange = {
                                armingDelaySeconds = it
                                prefs.edit().putInt(MonitoringService.PREF_ARMING_DELAY_SECONDS, it).apply()
                            },
                            alertGroupWindowSeconds = alertGroupWindowSeconds,
                            onAlertGroupWindowChange = {
                                alertGroupWindowSeconds = it
                                prefs.edit().putInt(SharedPrefsAlertGroupingStore.PREF_WINDOW_SECONDS, it).apply()
                            },
                            alertHourlyCap = alertHourlyCap,
                            onAlertHourlyCapChange = {
                                alertHourlyCap = it
                                prefs.edit().putInt(SharedPrefsAlertGroupingStore.PREF_HOURLY_CAP, it).apply()
                            },
                            encryptionViewModel = encryptionViewModel
                        )
                    }
                }
            }
        }
    }
}
