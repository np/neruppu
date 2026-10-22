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

package org.havenapp.neruppu.data.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.havenapp.neruppu.data.camera.analyzer.BlackFeedDetector
import org.havenapp.neruppu.data.camera.analyzer.MotionAnalyzer
import kotlinx.coroutines.flow.StateFlow
import org.havenapp.neruppu.data.camera.analyzer.ContextBatteryLevelProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var motionAnalyzer: MotionAnalyzer? = null
    private var camera: Camera? = null
    private val blackFeed = BlackFeedDetector()

    /** The bound camera has sent only uniformly black frames for a few seconds. */
    val feedBlack: StateFlow<Boolean> = blackFeed.black
     
    private val mutex = Mutex()
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
     
    var isBound = false
    var currentCameraSide: Boolean? = null
    var currentLifecycleOwner: LifecycleOwner? = null

    var sensitivityPref = 15f
    private var onMotionDetectedCallback: ((Double) -> Unit)? = null

    

    fun getCameraProvider(callback: (ProcessCameraProvider) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                callback(cameraProviderFuture.get())
            } catch (e: Exception) {
                Log.e("CameraManager", "Error getting camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Captures a JPEG into memory. Returns null on failure.
     *
     * Nothing readable touches the disk: the returned array is the only
     * plaintext copy, and the caller hands it straight to
     * MediaStorageRepository.saveImage(), which encrypts it.
     */
    suspend fun capturePhotoBytes(): ByteArray? = mutex.withLock {
        val capture = imageCapture ?: run {
            Log.e("CameraManager", "Cannot capture: imageCapture is null. Camera might not be bound to service.")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes = try {
                            // ImageCapture without OutputFileOptions yields a single
                            // JPEG-encoded plane.
                            val buffer = image.planes[0].buffer
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        } catch (e: Exception) {
                            Log.e("CameraManager", "Failed to read captured image", e)
                            null
                        } finally {
                            image.close()
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraManager", "CAPTURE FAILED: ${exception.message}", exception)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            )
        }
    }

    /**
     * A photo asked for on purpose (remote control): exposure is metered on
     * the centre first, and a nearly black result is retaken once after the
     * exposure has had time to settle. [dark] tells the caller it still is.
     */
    suspend fun captureMeteredPhoto(): MeteredPhoto? {
        meterCentre()
        var jpeg = capturePhotoBytes() ?: return null
        var brightness = meanBrightness(jpeg)
        if (brightness in 0 until DARK_BRIGHTNESS) {
            delay(RETAKE_DELAY_MS)
            meterCentre()
            capturePhotoBytes()?.let { retaken ->
                val b = meanBrightness(retaken)
                if (b > brightness) { jpeg = retaken; brightness = b }
            }
        }
        Log.d("CameraManager", "Metered photo: brightness $brightness")
        return MeteredPhoto(jpeg, dark = brightness in 0 until DARK_BRIGHTNESS)
    }

    class MeteredPhoto(val jpeg: ByteArray, val dark: Boolean)

    private suspend fun meterCentre() {
        val control = camera?.cameraControl ?: return
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        withTimeoutOrNull(METERING_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val future = control.startFocusAndMetering(action)
                future.addListener({ if (continuation.isActive) continuation.resume(Unit) }, ContextCompat.getMainExecutor(context))
            }
        }
    }

    fun getDifferenceMap(): Nothing? = null

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        useFrontCamera: Boolean,
        sensitivity: Int = 15,
        onMotionDetected: (Double) -> Unit
    ) {
        if (isBound && currentCameraSide == useFrontCamera && currentLifecycleOwner == lifecycleOwner) {
            Log.d("CameraManager", "Camera already bound to service lifecycle. Keeping active.")
            return
        }
        
        sensitivityPref = sensitivity.toFloat()
        onMotionDetectedCallback = onMotionDetected
        
        if (cameraExecutor.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        getCameraProvider { cameraProvider ->
            try {
                cameraProvider.unbindAll()
                blackFeed.reset()
                
                val useCases = mutableListOf<androidx.camera.core.UseCase>()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                        )
                    )
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                
                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                        )
                    )
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                useCases.add(imageCapture!!)

                motionAnalyzer = MotionAnalyzer(
                    onMotionDetected,
                    sensitivity,
                    ContextBatteryLevelProvider(context),
                    blackFeed
                )
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(analysisResolutionSelector)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, motionAnalyzer!!)
                    }
                useCases.add(imageAnalysis!!)

                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )
                
                isBound = true
                currentCameraSide = useFrontCamera
                currentLifecycleOwner = lifecycleOwner
                Log.d("CameraManager", "CAMERA SYSTEM INITIALIZED BY SERVICE: Bound to $lifecycleOwner")
            } catch (exc: Exception) {
                Log.e("CameraManager", "CRITICAL: Service-side camera binding failed", exc)
                isBound = false
            }
        }
    }


    fun unbind() {
        getCameraProvider { cameraProvider ->
            cameraProvider.unbindAll()
            isBound = false
            currentCameraSide = null
            currentLifecycleOwner = null
            imageCapture = null
            camera = null
            blackFeed.reset()
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
            motionAnalyzer?.cleanup()
            motionAnalyzer = null
            cameraExecutor.shutdown()
            Log.d("CameraManager", "Camera system released")
        }
    }

    fun release() {
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
    }

    companion object {
        /** Mean luma (0–255) under which a photo counts as black. */
        const val DARK_BRIGHTNESS = 16
        private const val RETAKE_DELAY_MS = 1_500L
        private const val METERING_TIMEOUT_MS = 2_000L

        /** Mean luma of a heavily downsampled decode, or -1 if it does not decode. */
        fun meanBrightness(jpeg: ByteArray): Int {
            val options = BitmapFactory.Options().apply { inSampleSize = 16 }
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return -1
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
            if (pixels.isEmpty()) return -1
            val sum = pixels.sumOf { p ->
                (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)).toLong()
            }
            return (sum / 1000 / pixels.size).toInt()
        }
    }
}
