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

package org.havenapp.neruppu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.havenapp.neruppu.BuildConfig
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.havenapp.neruppu.R
import org.havenapp.neruppu.data.audio.AudioRecorder
import org.havenapp.neruppu.data.camera.CameraManager
import org.havenapp.neruppu.data.sensors.*
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.model.SensorEvent
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.data.remote.GuardDevice
import org.havenapp.neruppu.data.remote.RemoteGuard
import org.havenapp.neruppu.domain.remote.RemoteStatus
import org.havenapp.neruppu.domain.repository.EncryptionKeyRepository
import org.havenapp.neruppu.domain.repository.MediaUploadRepository
import org.havenapp.neruppu.domain.repository.SensorRepository
import org.havenapp.neruppu.domain.usecase.HandleSensorEventUseCase
import org.havenapp.neruppu.domain.usecase.AttachAudioToEventUseCase
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    @Inject
    lateinit var sensorRepository: SensorRepository

    @Inject
    lateinit var handleSensorEventUseCase: HandleSensorEventUseCase

    @Inject
    lateinit var attachAudioToEventUseCase: AttachAudioToEventUseCase

    @Inject
    lateinit var alertTargetRepository: AlertTargetRepository

    @Inject
    lateinit var mediaUploadRepository: MediaUploadRepository

    @Inject
    lateinit var cameraManager: CameraManager

    @Inject
    lateinit var encryptionKeyRepository: EncryptionKeyRepository

    @Inject
    lateinit var remoteGuard: RemoteGuard

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLockRefreshJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorsJob: Job? = null
    private var heartbeatJob: Job? = null
    
    private lateinit var accelerometerDriver: AccelerometerDriver
    private lateinit var microphoneDriver: MicrophoneDriver
    private lateinit var lightSensorDriver: LightSensorDriver
    private lateinit var significantMotionDriver: SignificantMotionDriver
    private lateinit var audioRecorder: AudioRecorder

    private val _motionLevel = MutableStateFlow(0.0)
    val motionLevel: StateFlow<Double> = _motionLevel

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel

    private val _lightLevel = MutableStateFlow(0f)
    val lightLevel: StateFlow<Float> = _lightLevel

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    /**
     * Exit delay: monitoring is on but events are ignored until this
     * SystemClock.elapsedRealtime() deadline, so the user can leave the room.
     * 0 when idle. Sensors already run meanwhile, which lets their baselines
     * settle before the first event can fire.
     */
    private val _armedAtElapsed = MutableStateFlow(0L)
    val armedAtElapsed: StateFlow<Long> = _armedAtElapsed
    private var armingJob: Job? = null
    private var deliveryTickJob: Job? = null
    private var remoteJob: Job? = null
    @Volatile private var lastEventAt: Long? = null
    @Volatile private var lastEventType: SensorType? = null

    /** True once monitoring is on and the exit delay has elapsed. */
    private fun isArmed(): Boolean =
        _isMonitoring.value && SystemClock.elapsedRealtime() >= _armedAtElapsed.value

    private val _sessionStartTime = MutableStateFlow(0L)
    val sessionStartTime: StateFlow<Long> = _sessionStartTime

    private val _lastGuardedTime = MutableStateFlow(0L)
    val lastGuardedTime: StateFlow<Long> = _lastGuardedTime

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    inner class LocalBinder : Binder() {
        fun getService(): MonitoringService = this@MonitoringService
    }

    private val binder = LocalBinder()

    private var prefChangeJob: Job? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefChangeJob?.cancel()
        prefChangeJob = serviceScope.launch {
            delay(500) // debounce
            when (key) {
                "motion_sensitivity", "use_front_camera" -> {
                    Log.d("MonitoringService", "Prefs changed ($key), re-binding camera...")
                    updatePrefValues()
                    startMonitoring()
                }
                "audio_threshold", "save_photos" -> {
                    Log.d("MonitoringService", "Prefs changed ($key), updating values...")
                    updatePrefValues()
                    if (key == "audio_threshold") startMicrophoneMonitoring()
                }
            }
        }
    }

    private var savePhotosPref = true
    private var sensitivityPref = 15f
    private var useFrontCameraPref = false
    private var audioThresholdPref = 200f

    private fun updatePrefValues() {
        val prefs = getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
        savePhotosPref = prefs.getBoolean("save_photos", true)
        sensitivityPref = prefs.getFloat("motion_sensitivity", 15f)
        useFrontCameraPref = prefs.getBoolean("use_front_camera", false)
        audioThresholdPref = prefs.getFloat("audio_threshold", 200f)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    private var isRecordingAudio = AtomicBoolean(false)
    private var lastNoiseTime = 0L
    private var lastEventTime = ConcurrentHashMap<SensorType, Long>()
    private var microphoneJob: Job? = null
    private var uiSensorsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("MonitoringService", "onCreate [PID: ${Process.myPid()}]")
        
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                Log.d("MonitoringService", "Lifecycle event: $event")
            }
        })

        createNotificationChannel()
        updateNotification()

        accelerometerDriver = AccelerometerDriver(this)
        microphoneDriver = MicrophoneDriver()
        lightSensorDriver = LightSensorDriver(this)
        significantMotionDriver = SignificantMotionDriver(this)
        audioRecorder = AudioRecorder(this)
        
        updatePrefValues()
        val prefs = getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        _sessionStartTime.value = prefs.getLong("session_start_time", 0L)
        _lastGuardedTime.value = prefs.getLong("last_guarded_time", 0L)

        startHeartbeat()
        // Sensors will be started based on isMonitoring or UI visibility
        startMonitoringIfNeeded()
    }

    private fun startHeartbeat() {
        if (!BuildConfig.DEBUG) return
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                Log.v("MonitoringService", "HEARTBEAT - Monitoring: ${_isMonitoring.value}, UI: ${_uiActive.value}, PID: ${Process.myPid()}")
                delay(10000)
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neruppu:MonitoringWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L) // 10-minute timeout
            Log.d("MonitoringService", "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d("MonitoringService", "WakeLock released")
        }
    }

    private fun startWakeLockIfNeeded() {
        if (_isMonitoring.value) {
            acquireWakeLock()
            startWakeLockRefresh()
        }
    }

    private fun stopWakeLockIfNeeded() {
        if (!_isMonitoring.value) {
            wakeLockRefreshJob?.cancel()
            wakeLockRefreshJob = null
            releaseWakeLock()
        }
    }

    private fun startWakeLockRefresh() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = serviceScope.launch {
            while (isActive && _isMonitoring.value) {
                delay(480 * 1000L) // Refresh every ~8 minutes (before 10-min timeout)
                if (_isMonitoring.value) {
                    val timeout = calculateWakeLockTimeout()
                    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                        Log.d("MonitoringService", "WakeLock refreshed (adaptive timeout: ${timeout/1000/60}min)")
                    }
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neruppu:MonitoringWakeLock")
                    wakeLock?.acquire(timeout)
                }
            }
        }
    }

    private fun calculateWakeLockTimeout(): Long {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val chargingStatus = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = chargingStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        chargingStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

        return when {
            isCharging -> 10 * 60 * 1000L // 10 minutes when charging
            batteryLevel < 20 -> 5 * 60 * 1000L // 5 minutes when battery low
            batteryLevel < 50 -> 7 * 60 * 1000L // 7 minutes when medium battery
            else -> 10 * 60 * 1000L // 10 minutes when high battery
        }
    }

    private fun startMonitoringIfNeeded() {
        // We need the service to be "active" (startMonitoring) if:
        // 1. We are actually monitoring (background or foreground)
        // 2. The UI is active and needs a preview/sensor levels
        if (_isMonitoring.value || _uiActive.value) {
            startMonitoring()
        } else {
            stopMonitoring()
        }
    }

    private val _uiActive = MutableStateFlow(false)
    fun setUiActive(active: Boolean) {
        _uiActive.value = active
        if (active) {
            startMonitoring()
        } else {
            startMonitoringIfNeeded()
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        println("Neruppu: onStartCommand action=${intent?.action}")
        Log.d("MonitoringService", "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                Log.i("MonitoringService", "Stopping service via notification action")
                stopSelf()
            }
            "TRIGGER_MOTION_EVENT" -> {
                val level = intent.getDoubleExtra("motion_level", 0.0)
                serviceScope.launch {
                    handleEvent(SensorType.CAMERA_MOTION, "Camera motion detected: Level ${String.format("%.2f", level)}")
                }
            }
        }
        
        return START_STICKY
    }

    /**
     * Returns false without starting when no age recipient is configured.
     * Captures are encrypted at the source; with no key the only remaining
     * choices are writing plaintext or silently dropping evidence, so we
     * refuse loudly instead.
     */
    fun toggleMonitoring(): Boolean {
        if (!_isMonitoring.value && !encryptionKeyRepository.isConfigured) {
            Log.w("MonitoringService", "No age key configured - monitoring not started")
            notificationManager.notify(ERROR_NOTIFICATION_ID, createErrorNotification(
                "No age key configured — monitoring not started"
            ))
            return false
        }
        val now = System.currentTimeMillis()
        _isMonitoring.value = !_isMonitoring.value
        if (_isMonitoring.value) {
            Log.d("MonitoringService", "Monitoring ACTIVATED")
            _sessionStartTime.value = now
            getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
                .edit()
                .putLong("session_start_time", now)
                .remove("last_guarded_time")
                .apply()
            startWakeLockIfNeeded()
            startArming()
            startDeliveryTicks()
            startRemoteGuard()
        } else {
            Log.d("MonitoringService", "Monitoring DEACTIVATED")
            armingJob?.cancel()
            armingJob = null
            deliveryTickJob?.cancel()
            deliveryTickJob = null
            remoteJob?.cancel()
            remoteJob = null
            _armedAtElapsed.value = 0L
            _lastGuardedTime.value = now
            getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
                .edit()
                .remove("session_start_time")
                .putLong("last_guarded_time", now)
                .apply()
            stopWakeLockIfNeeded()
            updateNotification()
        }
        startMonitoringIfNeeded()
        return true
    }

    private fun startArming() {
        val delayMs = getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
            .getInt(PREF_ARMING_DELAY_SECONDS, DEFAULT_ARMING_DELAY_SECONDS)
            .coerceIn(0, MAX_ARMING_DELAY_SECONDS) * 1000L
        _armedAtElapsed.value = SystemClock.elapsedRealtime() + delayMs
        updateNotification()

        armingJob?.cancel()
        armingJob = serviceScope.launch {
            delay(delayMs)
            if (!_isMonitoring.value) return@launch
            Log.i("MonitoringService", "Exit delay elapsed: guarding ARMED")
            updateNotification()
            notificationManager.notify(ARMED_NOTIFICATION_ID, createArmedNotification())
        }
        Log.d("MonitoringService", "Arming: events ignored for ${delayMs / 1000}s")
    }

    private fun stopMonitoring() {
        Log.d("MonitoringService", "stopMonitoring() called - Monitoring: ${_isMonitoring.value}, UI: ${_uiActive.value}")
        
        // ONLY unbind camera if NO ONE needs it (not monitoring AND UI is not visible)
        if (!_isMonitoring.value && !_uiActive.value) {
            Log.d("MonitoringService", "Releasing camera system (Idle & UI hidden)")
            cameraManager.unbind()
        } else {
            Log.d("MonitoringService", "Keeping camera bound for Preview/UI")
        }

        microphoneJob?.cancel()
        microphoneJob = null
        uiSensorsJob?.cancel()
        uiSensorsJob = null
        sensorsJob?.cancel()
        sensorsJob = null
    }


    private fun startMonitoring() {
        Log.d("MonitoringService", "startMonitoring() called - Monitoring: ${_isMonitoring.value}, UI: ${_uiActive.value}")
        
// Always bind camera if we are either Monitoring OR UI is active
        if (_isMonitoring.value || _uiActive.value) {
            cameraManager.bindCamera(
                lifecycleOwner = this,
                useFrontCamera = useFrontCameraPref,
                sensitivity = (sensitivityPref * 30).toInt().coerceIn(5, 30),
                onMotionDetected = { level ->
                    _motionLevel.value = level
                    if (isArmed() && level > 15.0) {
                        Log.v("MonitoringService", "THRESHOLD EXCEEDED: $level")
                        serviceScope.launch {
                            handleEvent(SensorType.CAMERA_MOTION, "Camera motion detected: Level ${String.format("%.2f", level)}")
                        }
                    }
                }
            )
        }

        if (sensorsJob?.isActive == true) {
            Log.d("MonitoringService", "Sensors already running. Skipping re-initialization.")
            return
        }
        
// Start sensor data flows only if we are monitoring
        // UI preview only needs the camera (handled above)
        if (_isMonitoring.value) {
            sensorsJob = serviceScope.launch {
                Log.v("MonitoringService", "Launching sensor flows...")
                
                val accelerometerFlow = accelerometerDriver.observeMotion()
                    .buffer()
                    .onEach { magnitude ->
                        Log.v("MonitoringService", "Motion event received")
                        serviceScope.launch {
                            handleEvent(SensorType.ACCELEROMETER, "Physical motion detected: Magnitude $magnitude")
                        }
                    }

                val lightFlow = lightSensorDriver.observeLightChanges()
                    .buffer()
                    .onEach { lux ->
                        _lightLevel.value = lux
                        Log.v("MonitoringService", "Light event received")
                        serviceScope.launch {
                            handleEvent(SensorType.LIGHT, "Light change detected: $lux lux")
                        }
                    }

                launch { accelerometerFlow.collect() }
                launch { lightFlow.collect() }
                
                // Significant Motion Monitoring (Ultra-low power wakeups)
                launch {
                    significantMotionDriver.observeSignificantMotion().collect {
                        handleEvent(SensorType.ACCELEROMETER, "Significant motion detected (hardware trigger)")
                    }
                }
                
                startMicrophoneMonitoring()
            }
        } else if (_uiActive.value) {
            // If UI is active but NOT monitoring, we still want to show levels
            // but we can be more conservative or just show camera
            uiSensorsJob?.cancel()
            uiSensorsJob = serviceScope.launch {
                launch {
                    lightSensorDriver.observeLightChanges()
                        .conflate()
                        .collect { _lightLevel.value = it }
                }
                launch {
                    microphoneDriver.observeNoise()
                        .conflate()
                        .collect { _audioLevel.value = it.toFloat() }
                }
            }
        }
    }

    private var fastBaseline = 0f
    private var slowBaseline = 0f

    private fun startMicrophoneMonitoring() {
        microphoneJob?.cancel()
        
        microphoneJob = serviceScope.launch {
            microphoneDriver.observeNoise()
                .conflate()
                .collect { amplitude ->
                    _audioLevel.value = amplitude.toFloat()
                    
                    val amp = amplitude.toFloat()
                    if (fastBaseline == 0f) {
                        fastBaseline = amp
                        slowBaseline = amp
                    }
                    
                    // Dual EMA: Fast adapts in ~0.2s (sensitive), Slow adapts in ~1000s (stable noise floor)
                    fastBaseline = 0.5f * fastBaseline + 0.5f * amp
                    slowBaseline = 0.999f * slowBaseline + 0.001f * amp
                    
                    val spike = fastBaseline - slowBaseline
                    
                    // Adjust sensitivity based on scale. 
                    val threshold = if (audioThresholdPref <= 1.0f) {
                        // Map slider 0.0-1.0 to RMS 50-2000
                        50f + (audioThresholdPref * 1950f)
                    } else {
                        audioThresholdPref
                    }

                    // CRITICAL: Check threshold BEFORE compareAndSet to avoid blocking
                    if (isArmed() && spike > threshold && isRecordingAudio.compareAndSet(false, true)) {
                        Log.i("MonitoringService", "AUDIO TRIGGER (SPIKE): $spike (Threshold: $threshold, Fast: $fastBaseline, Slow: $slowBaseline)")
                        serviceScope.launch {
                            val eventId = handleEvent(SensorType.MICROPHONE, "Acoustic spike detected: ${spike.toInt()}")
                            startAudioRecording(eventId)
                        }
                    } else if (spike > (threshold * 0.5f)) {
                        // Log near-misses to help user calibrate
                        Log.v("MonitoringService", "Near-miss spike: $spike (Threshold: $threshold)")
                    }
                }
        }
    }

    private fun startAudioRecording(eventId: Long = -1) {
        // isRecordingAudio is already set to true in the trigger block
        
        val captureDuration = (getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
            .getFloat("audio_capture_duration", 5f) * 1000).toLong()

        serviceScope.launch {
            var audioFile: File? = null
            try {
                // STOP microphone monitoring to release the AudioRecord instance
                Log.d("MonitoringService", "Stopping microphone monitoring to allow recording...")
                microphoneJob?.cancel()
                microphoneJob = null
                // Wait a bit for the driver to close the AudioRecord
                delay(400)

                Log.d("MonitoringService", "Capturing ${captureDuration/1000}s audio clip of the event...")
                audioFile = audioRecorder.startRecording()
                if (audioFile == null) {
                    Log.e("MonitoringService", "Failed to start AudioRecorder")
                    return@launch
                }

                delay(captureDuration)
            } catch (e: Exception) {
                Log.e("MonitoringService", "Audio recording failed", e)
            } finally {
                // stopRecording MUST be called to finalize the file and release MediaRecorder
                val uri = audioRecorder.stopRecording()
                
                // RESUME monitoring ASAP
                isRecordingAudio.set(false)
                Log.d("MonitoringService", "Resetting audio recording flag and restarting monitoring.")
                startMicrophoneMonitoring()
                
                // Process the captured file and alerts OUTSIDE the monitoring pause
                if (uri != null && audioFile != null && eventId != -1L) {
                    Log.i("MonitoringService", "AUDIO CLIP CAPTURED: $uri. Attaching to event $eventId in background...")
                    serviceScope.launch {
                        // Clean up temp file first - the attach will copy it to captures dir
                        val tempFile = audioFile
                        attachAudioToEventUseCase.execute(eventId, tempFile, System.currentTimeMillis())
                        // Delete temp file after attach (success or failure - it's copied to permanent storage)
                        try {
                            tempFile.delete()
                            Log.d("MonitoringService", "Temp audio file deleted: ${tempFile.absolutePath}")
                        } catch (e: Exception) {
                            Log.w("MonitoringService", "Failed to delete temp audio file", e)
                        }
                        deliverAlerts(eventId)
                    }
                }
            }
        }
    }

    private suspend fun handleEvent(type: SensorType, description: String): Long {
        println("Neruppu: handleEvent type=$type")
        if (!isArmed()) {
            Log.d("MonitoringService", "Event ignored, not armed (exit delay or idle): $type")
            return -1
        }
        lastEventAt = System.currentTimeMillis()
        lastEventType = type
        val now = System.currentTimeMillis()
        val lastTime = lastEventTime[type] ?: 0L
        
        // Cooldown: 3 seconds for the same type, unless it's a microphone event which has its own logic
        if (type != SensorType.MICROPHONE && now - lastTime < 3000) {
            Log.d("MonitoringService", "Event suppressed by cooldown: $type")
            return -1
        }
        
        lastEventTime[type] = now

        Log.i("MonitoringService", ">>> TRIGGERING EVENT: $type - $description")
        
        // 1. Capture photo bytes if possible
        val imageBytes: ByteArray? = if (savePhotosPref) {
            try {
                Log.d("MonitoringService", "Attempting to capture photo for event...")
                cameraManager.capturePhotoBytes()
            } catch (e: Exception) {
                Log.e("MonitoringService", "CRITICAL FAILURE: Photo capture threw exception", e)
                null
            }
        } else null

        // 2. Prepare SensorEvent for Use Case
        val sensorEvent = SensorEvent(
            sensorType = type,
            description = description,
            timestamp = now,
            imageBytes = imageBytes
        )

        // 3. Orchestrate via Use Case (saves locally + logs in DB + sends Matrix alert)
        println("Neruppu: Calling HandleSensorEventUseCase for $type")
        Log.d("MonitoringService", "Calling HandleSensorEventUseCase for $type")
        val result = handleSensorEventUseCase.execute(sensorEvent)
        println("Neruppu: UseCase result=$result")
        val id = result.getOrDefault(-1L)
        
        if (result.isFailure) {
            Log.e("MonitoringService", "UseCase failed", result.exceptionOrNull())
        }

        // Microphone events are delivered once their audio clip is attached.
        if (id > 0 && type != SensorType.MICROPHONE) {
            serviceScope.launch { deliverAlerts(id) }
        }

        // Posted directly, not through WorkManager: in Doze, jobs wait for
        // maintenance windows that can be hours apart.
        postEventNotification(type, description)

        Log.i("MonitoringService", "EVENT PROCESSED: $type (Local ID: $id)")
        return id
    }

    /**
     * Sends pending alerts right away from this foreground service, which keeps
     * network access in Doze, unlike WorkManager jobs that are deferred to
     * maintenance windows. WorkManager is only the fallback when this fails.
     */
    private suspend fun deliverAlerts(eventId: Long) {
        if (alertTargetRepository.activeTarget == AlertTarget.NONE) return
        val delivered = try {
            mediaUploadRepository.uploadPendingEvents()
        } catch (e: Exception) {
            Log.w("MonitoringService", "Direct alert delivery failed for event $eventId", e)
            false
        }
        if (!delivered) {
            try {
                mediaUploadRepository.enqueueUpload(eventId)
            } catch (e: Exception) {
                Log.w("MonitoringService", "Failed to enqueue media upload for event $eventId", e)
            }
        }
    }

    /**
     * While guarding, sends incident summaries and retries failed alerts on
     * time; the WorkManager runs scheduled for that would be deferred in Doze.
     * Cheap when there is nothing to do: one DB query.
     */
    private fun startDeliveryTicks() {
        deliveryTickJob?.cancel()
        deliveryTickJob = serviceScope.launch {
            while (isActive && _isMonitoring.value) {
                delay(DELIVERY_TICK_MS)
                if (alertTargetRepository.activeTarget == AlertTarget.NONE) continue
                try {
                    mediaUploadRepository.uploadPendingEvents()
                } catch (e: Exception) {
                    Log.w("MonitoringService", "Periodic alert delivery failed", e)
                }
            }
        }
    }

    /** Answers the paired remote control (status, photo) while guarding. */
    private fun startRemoteGuard() {
        remoteJob?.cancel()
        remoteJob = serviceScope.launch {
            try {
                remoteGuard.run(guardDevice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MonitoringService", "Remote control stopped", e)
            }
        }
    }

    private val guardDevice = object : GuardDevice {
        override suspend fun status(): RemoteStatus {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val remainingMs = _armedAtElapsed.value - SystemClock.elapsedRealtime()
            return RemoteStatus(
                monitoring = _isMonitoring.value,
                armed = isArmed(),
                armingRemainingSeconds = if (_isMonitoring.value && remainingMs > 0) ((remainingMs + 999) / 1000).toInt() else 0,
                guardingSince = _sessionStartTime.value.takeIf { it > 0 },
                batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                pluggedIn = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
                lastEventAt = lastEventAt,
                lastEventType = lastEventType?.name
            )
        }

        // Only while guarding: the camera is bound then. The photo is not an
        // event and is not stored; it leaves the phone age-encrypted.
        override suspend fun takePhoto(): ByteArray? =
            if (_isMonitoring.value) cameraManager.capturePhotoBytes() else null
    }

    private fun postEventNotification(type: SensorType, description: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Security Event: ${type.name}")
            .setContentText(description)
            .setSmallIcon(R.drawable.ic_stat_security)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            // One id: a new event replaces the previous notification.
            notificationManager.notify(EVENT_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("MonitoringService", "Notification permission missing", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        // Separate channel so "guarding is now active" can be audible while the
        // ongoing service notification stays silent; tunable in system settings.
        manager.createNotificationChannel(NotificationChannel(
            STATUS_CHANNEL_ID,
            "Guard status",
            NotificationManager.IMPORTANCE_DEFAULT
        ))
    }

    private fun updateNotification() {
        val notification = createNotification()
        if (_isMonitoring.value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                           ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Still show a simple notification if UI is active? 
            // Or just remove it. Let's remove it for now when not monitoring.
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, MonitoringService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val remainingMs = _armedAtElapsed.value - SystemClock.elapsedRealtime()
        val arming = _isMonitoring.value && remainingMs > 0
        val contentText = when {
            arming -> "Exit delay: leave the room before guarding starts"
            _isMonitoring.value -> "System is active and monitoring sensors"
            else -> "System is idle. Tap START to begin monitoring."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neruppu Security")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_security)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .apply {
                if (arming) {
                    // The system renders the countdown: no per-second updates.
                    setWhen(System.currentTimeMillis() + remainingMs)
                    setShowWhen(true)
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                }
            }
            .build()
    }

    private fun createArmedNotification(): Notification {
        return NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setContentTitle("Neruppu Security")
            .setContentText("Guarding active: events are now recorded")
            .setSmallIcon(R.drawable.ic_stat_security)
            .setAutoCancel(true)
            .setTimeoutAfter(ARMED_NOTIFICATION_TIMEOUT_MS)
            .build()
    }

    private fun createErrorNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neruppu Security")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_security)
            .setAutoCancel(true)
            .build()
    }

    override fun onDestroy() {
        Log.d("MonitoringService", "onDestroy [PID: ${Process.myPid()}]")
        super.onDestroy()
        getSharedPreferences("neruppu_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        stopWakeLockIfNeeded()
        heartbeatJob?.cancel()
        armingJob?.cancel()
        deliveryTickJob?.cancel()
        remoteJob?.cancel()
        sensorsJob?.cancel()
        uiSensorsJob?.cancel()
        serviceScope.cancel()
        cameraManager.release()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ERROR_NOTIFICATION_ID = 2
        const val ARMED_NOTIFICATION_ID = 3
        const val EVENT_NOTIFICATION_ID = 4
        private const val DELIVERY_TICK_MS = 60_000L
        const val CHANNEL_ID = "monitoring_channel"
        const val STATUS_CHANNEL_ID = "guard_status_channel"

        const val PREF_ARMING_DELAY_SECONDS = "arming_delay_seconds"
        const val DEFAULT_ARMING_DELAY_SECONDS = 60
        const val MAX_ARMING_DELAY_SECONDS = 15 * 60
        private const val ARMED_NOTIFICATION_TIMEOUT_MS = 10 * 60 * 1000L
        const val ACTION_STOP_SERVICE = "org.havenapp.neruppu.STOP_SERVICE"
    }
}
