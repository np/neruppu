# Patches to apply to Neruppu (HEAD 31785869)

> **Status: applied in this repository.** Kept as the design record.
> Deviations from the text below:
> - §1: the AAR comes from a repo-local Maven repo (`build-aar.sh`), not
>   `files(...)`, which AGP 8 rejects in library modules.
> - §3: monitoring is started by `MonitoringService.toggleMonitoring()` on the
>   bound service, not `onStartCommand`, so the guard lives there (returns
>   false + error notification). `MainActivity` checks the key *before*
>   `startForegroundService()` so a refused start cannot crash the service.
>   Keys are entered in Settings → Encryption (`EncryptionSettingsViewModel`
>   over a domain `EncryptionKeyRepository`).
> - §5/§6: uploaded blobs are named `<timestamp>.age`, not the local filename
>   (`neruppu_sound_….mp4.age` would reveal the sensor type). On Matrix the
>   initial text event is opaque too, and the upload mimetype is
>   `application/octet-stream`.
> - §8 (audio without a plaintext window) remains open.

---

## 1. Gradle wiring

`age-encryption/` produces `neruppu-age.aar` (see `../age-encryption/BUILD.md`).

`data/build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("$rootDir/android/libs/neruppu-age.aar"))
    // already present: androidx.security.crypto
}
```

The AAR carries the Go runtime (~2–4 MB per ABI). Enable ABI splits if size matters.

---

## 2. `MonitoringService.handleEvent` — use the in-memory capture

```diff
         val imageBytes: ByteArray? = if (savePhotosPref) {
             try {
                 Log.d("MonitoringService", "Attempting to capture photo for event...")
-                val uri = cameraManager.capturePhoto()
-                withContext(Dispatchers.IO) {
-                    uri?.let { contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() } }
-                }
+                cameraManager.capturePhotoBytes()
             } catch (e: Exception) {
                 Log.e("MonitoringService", "CRITICAL FAILURE: Photo capture threw exception", e)
                 null
             }
         } else null
```

---

## 3. Refuse to run unconfigured

Monitoring must not start without a recipient, otherwise the only safe
behaviour left is to drop captures silently. Fail loudly instead — this is the
defect found in `MatrixConfigStore` (silent fallback to plaintext prefs), and
it should not be reproduced here.

In `MonitoringService.onStartCommand`, before `startMonitoring()`:

```kotlin
if (!ageEncryptor.isConfigured) {
    notificationManager.notify(
        ERROR_NOTIFICATION_ID,
        buildErrorNotification("No age key configured — monitoring not started")
    )
    stopSelf()
    return START_NOT_STICKY
}
```

And grey out the start button in `MainActivity` with the same condition.

---

## 4. `MediaUploadRepositoryImpl.guessMimeType`

```diff
     private fun guessMimeType(path: String): String {
         return when {
+            path.endsWith(".age", ignoreCase = true) ->
+                MediaStorageRepositoryImpl.MIME_AGE
             path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
```

Put the `.age` arm **first**: `neruppu_motion_123.jpg.age` ends with `.age`,
but a naive order would still match `.jpg` if that arm came first and the
suffix were stripped. Keeping both suffixes is useful locally (you can see what
a blob holds without decrypting) and harmless, since the filename is not
uploaded.

---

## 5. Transports: send blobs as documents, never as media

### 5a. `TelegramApiClient` — add

```kotlin
suspend fun sendDocument(
    bytes: ByteArray,
    caption: String,
    fileName: String
): Result<Unit> = runCatching {
    val response: HttpResponse = client.post("$baseUrl/sendDocument") {
        val formData = formData {
            append("chat_id", configStore.chatId)
            append("caption", caption)
            append("document", bytes, Headers.build {
                append(HttpHeaders.ContentType, "application/octet-stream")
                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
            })
        }
        setBody(MultiPartFormDataContent(formData))
    }
    if (response.status.value !in 200..299) {
        error("Telegram sendDocument failed: ${response.status}")
    }
}
```

`sendPhoto` would re-encode server-side and destroy the ciphertext; `sendVoice`
would reject it. `sendDocument` is the only API that passes bytes through
untouched.

### 5b. `TelegramAlertTransport.send`, MediaAlert branch

```diff
                 is AlertPayload.MediaAlert -> {
                     val file = payload.mediaFile
                     val bytes: ByteArray = File(file.absolutePath).readBytes()
 
-                    if (file.mimeType == "image/jpeg") {
-                        val uploadBytes = if (bytes.size > 800_000) {
-                            compressJpeg(bytes, targetKb = 800)
-                        } else bytes
-                        apiClient.sendPhoto(uploadBytes, textMsg).getOrThrow()
-                    } else {
-                        apiClient.sendVoice(bytes, textMsg).getOrThrow()
-                    }
+                    when (file.mimeType) {
+                        MediaStorageRepositoryImpl.MIME_AGE -> {
+                            // NEVER route a blob through compressJpeg(): it calls
+                            // BitmapFactory.decodeByteArray, which returns null on
+                            // ciphertext and would silently upload the original —
+                            // or, worse, a re-encoded corruption.
+                            apiClient.sendDocument(
+                                bytes,
+                                textMsg,
+                                file.absolutePath.substringAfterLast('/')
+                            ).getOrThrow()
+                        }
+                        "image/jpeg" -> {
+                            val uploadBytes = if (bytes.size > 800_000) {
+                                compressJpeg(bytes, targetKb = 800)
+                            } else bytes
+                            apiClient.sendPhoto(uploadBytes, textMsg).getOrThrow()
+                        }
+                        else -> apiClient.sendVoice(bytes, textMsg).getOrThrow()
+                    }
                 }
```

### 5c. `MatrixApiClient` — add an `m.file` variant

`performSendMedia` already takes a msgType, so only a public entry point is
needed:

```kotlin
suspend fun sendFileEvent(
    mxcUri: String,
    caption: String,
    fileName: String,
    mimeType: String = "application/octet-stream",
    txnId: String = UUID.randomUUID().toString()
): Result<Unit> = runCatching {
    val resolvedRoomId = resolveRoomId(configStore.roomId).getOrThrow()
    val result = performSendMedia(resolvedRoomId, "m.file", mxcUri, fileName, mimeType, txnId)
    if (result.isFailure) {
        joinRoom(resolvedRoomId).onSuccess {
            return performSendMedia(resolvedRoomId, "m.file", mxcUri, fileName, mimeType, txnId)
        }
    }
    result.getOrThrow()
}
```

Note that `m.file`'s `body` is conventionally the filename, not a caption —
hence `fileName` in the body slot.

### 5d. `MatrixAlertTransport`, MediaAlert branch

Skip `compressJpeg` for `MIME_AGE` (same reason as 5b) and dispatch to
`sendFileEvent`:

```diff
-                    val uploadBytes = if (file.mimeType == "image/jpeg" && bytes.size > 800_000) {
+                    val isBlob = file.mimeType == MediaStorageRepositoryImpl.MIME_AGE
+                    val uploadBytes = if (!isBlob && file.mimeType == "image/jpeg" && bytes.size > 800_000) {
                         compressJpeg(bytes, targetKb = 800)
                     } else bytes
```

```diff
-                    val mediaResult = if (file.mimeType.startsWith("image/")) {
+                    val mediaResult = if (isBlob) {
+                        apiClient.sendFileEvent(mxcUri, textMsg, fileName)
+                    } else if (file.mimeType.startsWith("image/")) {
```

The Matrix media repo is **not** end-to-end encrypted (see the audit), which is
exactly why this matters: the homeserver now stores a blob it cannot read.

---

## 6. Caption hygiene — the remaining leak

`AlertMessageFormatter.format(payload)` is sent **in clear** as the caption
alongside every blob, on both transports. With the payload encrypted, the
caption becomes the only thing an observer learns, so it should say the
minimum: a timestamp and nothing else.

```kotlin
// AlertMessageFormatter
fun formatOpaque(payload: AlertPayload): String =
    "⚠ ${Instant.ofEpochMilli(payload.timestamp)}"
```

Use `formatOpaque` whenever `mimeType == MIME_AGE`. Dropping the sensor type is
deliberate: "microphone" vs "motion" tells an observer what happened in the
room.

---

## 7. Local gallery

`LogsScreen` loads captures through Coil by path. Against a blob this renders
nothing. Either hide thumbnails when the path ends in `.age`, or show a lock
glyph. There is no way around it: the device holds no private key, by design.

Metadata (timestamp, sensor type, upload status) stays visible from Room, which
remains unencrypted — acceptable, and unchanged by this work.

---

## 8. Audio, to close the last plaintext window

Out of scope for the prototype. `saveAudio` currently receives a plaintext
`.mp4` written by `MediaRecorder` through its own fd, encrypts it and shreds
the original — a window of a few seconds in app-private storage.

To eliminate it: drop `MediaRecorder`, use `AudioRecord` (already present for
level monitoring) feeding `MediaCodec` in AAC, and write **ADTS** frames into
`AgeEncryptor.openStream()`. ADTS is self-framing, so `age -d` yields a
playable `.aac` with no container of our own — which is precisely the trap
Cryptocam fell into for video, forcing their companion tool.
