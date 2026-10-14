/*
 * Copyright (C) 2026 thamizh-root
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.havenapp.neruppu.data.storage

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.havenapp.neruppu.data.BuildConfig
import org.havenapp.neruppu.data.crypto.AgeEncryptor
import org.havenapp.neruppu.domain.model.MediaFile
import org.havenapp.neruppu.domain.repository.MediaStorageRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MODIFIED for the age prototype.
 *
 * Every capture is encrypted before it reaches the filesystem. The two entry
 * points below are the only places anything is written to captures/, which is
 * what makes this a safe seam.
 *
 * Mime type is MIME_AGE regardless of the payload: downstream, transports must
 * treat it as an opaque blob (document / m.file), never as an image, or they
 * will try to decode it. The original type is preserved only in the FILENAME
 * suffix (…​.jpg.age), which is local and never uploaded.
 */
@Singleton
class MediaStorageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptor: AgeEncryptor
) : MediaStorageRepository {

    override suspend fun saveImage(
        jpegBytes: ByteArray,
        timestamp: Long
    ): Result<MediaFile> = runCatching {
        withContext(Dispatchers.IO) {
            val ciphertext = encryptor.encrypt(jpegBytes)
            val file = File(capturesDir(), "neruppu_motion_$timestamp.jpg.age")
            file.writeBytes(ciphertext)

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Encrypted image: ${jpegBytes.size} B -> ${file.length()} B")
            }

            MediaFile(
                absolutePath = file.absolutePath,
                mimeType = MIME_AGE,
                sizeBytes = file.length(),
                timestamp = timestamp
            )
        }
    }

    /**
     * Audio still arrives as a plaintext temp file produced by MediaRecorder,
     * which writes through its own fd. We encrypt it here and shred the
     * original immediately.
     *
     * This leaves a plaintext window of a few seconds in filesDir (app-private,
     * not world-readable). Closing it entirely means replacing MediaRecorder
     * with AudioRecord + MediaCodec writing ADTS frames into
     * [AgeEncryptor.openStream] — see PATCHES.md, step 6. Deliberately out of
     * scope for this prototype.
     */
    override suspend fun saveAudio(
        audioFile: File,
        timestamp: Long
    ): Result<MediaFile> = runCatching {
        withContext(Dispatchers.IO) {
            val file = File(capturesDir(), "neruppu_sound_$timestamp.mp4.age")

            encryptor.openStream(file).use { out ->
                audioFile.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            }

            // Overwrite before unlinking. On flash storage this is not a real
            // erase, but it beats leaving the extents intact for a casual
            // undelete, and it costs nothing at this size.
            runCatching {
                val length = audioFile.length()
                if (length in 1..MAX_SHRED_BYTES) {
                    audioFile.outputStream().use { it.write(ByteArray(length.toInt())) }
                }
                audioFile.delete()
            }.onFailure {
                Log.w(TAG, "Could not shred plaintext audio temp file")
            }

            MediaFile(
                absolutePath = file.absolutePath,
                mimeType = MIME_AGE,
                sizeBytes = file.length(),
                timestamp = timestamp
            )
        }
    }

    override fun getNeruppuFolder(): File = capturesDir()

    private fun capturesDir(): File =
        File(context.filesDir, "captures").apply { mkdirs() }

    companion object {
        const val MIME_AGE = "application/age-encryption"
        private const val TAG = "MediaStorageRepository"
        private const val MAX_SHRED_BYTES = 8L * 1024 * 1024
    }
}
