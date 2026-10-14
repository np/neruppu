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
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.havenapp.neruppu.data.camera.analyzer.MotionAnalyzer
import org.havenapp.neruppu.data.camera.analyzer.ContextBatteryLevelProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var motionAnalyzer: MotionAnalyzer? = null
     
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
                    ContextBatteryLevelProvider(context)
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

                cameraProvider.bindToLifecycle(
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
}
