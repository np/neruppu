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

package org.havenapp.neruppu.data.crypto

import android.os.ParcelFileDescriptor
import neruppuage.Neruppuage
import neruppuage.Writer
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when monitoring is asked to capture with no recipient configured. */
class NoRecipientConfiguredException :
    IllegalStateException("No age recipient configured; refusing to write plaintext capture")

/**
 * Encrypts captures to the configured age recipient.
 *
 * Output is a plain age file: `age -d -i key.txt capture.age > capture.jpg`
 * yields a usable JPEG with any age implementation. No custom container, no
 * plaintext header, no recipient fingerprints — unlike Cryptocam, which needs
 * its companion tool because its format wraps a container for video framing.
 *
 * There is no decryption here on purpose. The private key never touches the
 * guarded device, so a seized phone cannot read what it recorded (only the
 * paired remote-control phone can, see data/remote).
 */
@Singleton
class AgeEncryptor @Inject constructor(
    private val recipientStore: AgeRecipientStore
) {
    val isConfigured: Boolean get() = recipientStore.isConfigured

    /** One-shot path for payloads already in memory (a JPEG from CameraX). */
    @Throws(NoRecipientConfiguredException::class)
    fun encrypt(plaintext: ByteArray): ByteArray {
        val recipients = requireRecipients()
        return Neruppuage.encryptBytes(plaintext, recipients)
    }

    /**
     * Streaming path, for payloads that are produced incrementally (an audio
     * encoder). The returned stream writes ciphertext straight into [target];
     * nothing readable ever lands on disk.
     *
     * The caller MUST close the stream: an age stream that is not finalized
     * cannot be decrypted at all — the partial file is lost, not truncated.
     */
    @Throws(NoRecipientConfiguredException::class)
    fun openStream(target: File): OutputStream {
        val recipients = requireRecipients()
        val pfd = ParcelFileDescriptor.open(
            target,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        )
        // detachFd() hands ownership to Go; the runtime must not close it
        // underneath us. Writer.Close() closes the underlying file.
        val fd = pfd.detachFd()
        return try {
            AgeOutputStream(Neruppuage.newWriterToFd(fd.toLong(), recipients))
        } catch (t: Throwable) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            throw t
        }
    }

    private fun requireRecipients(): String {
        val recipients = recipientStore.recipients
        if (recipients.isBlank()) throw NoRecipientConfiguredException()
        return recipients
    }
}

/**
 * Adapts the gomobile [Writer] to [OutputStream].
 *
 * Note the short-write handling: Cryptocam's equivalent loops on
 * `written += write(buffer)` and re-sends the WHOLE buffer each time instead of
 * the remainder, which would corrupt the stream if a partial write ever
 * occurred. Here the slice advances.
 */
private class AgeOutputStream(private val writer: Writer) : OutputStream() {

    private var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!closed) { "stream closed" }
        var written = 0
        while (written < len) {
            val slice = b.copyOfRange(off + written, off + len)
            val n = writer.write(slice).toInt()
            if (n <= 0) throw java.io.IOException("age writer made no progress")
            written += n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Finalizes the age STREAM (last chunk + tag) and closes the fd.
        writer.close()
    }
}
