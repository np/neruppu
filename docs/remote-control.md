# Remote control

A second phone running Neruppu can ask the guarded phone for its **status**
and a **photo on demand**. It cannot stop guarding, and there is no live
video or microphone. Everything travels through an ntfy server
(`https://ntfy.sh` by default, or your own) and is end-to-end encrypted:
the server only sees opaque text and age files.

## Pairing

1. Remote phone: Settings → Remote control → *Use this phone as the remote*.
   It generates:
   - an age identity (private key, stays on the remote) and its public key;
   - a random 32-byte channel key;
   - a random topic (`nrp…`, 128 bits).
   It shows them as a QR code (or a copyable `neruppu-pair:v1:` code).
   It also publishes **its own settings** on the channel (see below).
2. Guarded phone: Settings → Remote control → *Scan the remote's code*.
   It stores the channel, **adds the remote's age public key** to
   Settings → Encryption (so its captures can be opened by the remote), and
   **applies the remote's settings**.

### Settings handed over

Configure the remote first, as if it were the phone to guard; the guarded
phone takes, at pairing time only:

- sensors and sensitivities, photo saving, front/back camera, audio clip
  length;
- exit delay, alert grouping window, hourly cap;
- the active alert channel with its credentials (Telegram, Matrix or the
  full HTTPS template); the guarded phone's previous channel is replaced;
- the remote's encryption keys (e.g. your computer's age key), added to the
  guarded phone's.

The settings are a `DeviceConfig` JSON (only a fixed list of preference keys
is read and applied), sealed with the channel key in the `cfg` direction and
published on `<topic>-p` (as an attachment when longer than ntfy's 4 KB
message limit). The guarded phone polls that topic right after scanning and
applies the newest one under 24 h old. Changed a setting on the remote after
creating the pairing? *Send settings again*, then scan again on the guarded
phone (allowed while already paired). The remote cannot change the guarded
phone's settings afterwards: a later message on `<topic>-p` is never read
while guarding, so a stolen remote cannot redirect alerts.

Anyone who sees the code can command the guarded phone: show it only while
pairing. A phone paired as remote refuses to become a guarded phone, and its
Home tab is replaced by the Remote screen: a phone holding an age private key
never guards. Unpair in the same dialog on each phone.

## Biometric lock (remote phone)

The remote's secrets (age identity, channel key, topic) are sealed with an
AES-256-GCM key in the Android Keystore that requires **strong biometrics
or the device PIN/pattern/password within the last 5 minutes**
(`data/remote/SecretVault.kt`). Without that unlock even the app cannot read
them, so someone holding the unlocked phone, or a copy of its storage, gets
neither the photos nor the commands. The Remote tab asks on opening, hides
everything and locks again when the app leaves the foreground; showing the
pairing code, creating it and sending settings ask too.

- Becoming a remote requires a secure lock screen.
- Removing or resetting the screen lock can invalidate the key: the remote
  then has to be unpaired and paired again.
- A remote paired before this lock is migrated at its first unlock.
- The guarded phone is not locked: it must answer unattended, and it holds
  no private key.

## Protocol

- Commands go to `<topic>-c`, answers to `<topic>-r`, pairing settings to
  `<topic>-p`.
- Each ntfy message body is `nrp1.` + base64url(nonce ‖ AES-256-GCM
  ciphertext) with the channel key; the additional data binds the topic and
  the direction, so an answer cannot be replayed as a command.
- Commands: `{"v":1,"id":<uuid>,"ts":<ms>,"cmd":"status"|"photo"}`.
  The guarded phone ignores anything it cannot decrypt, ids it has already
  seen, and timestamps more than 5 minutes off its clock.
- Answers: `{"v":1,"id","ts","re":<command id>,"kind":"status"|"photo"|"error", "status":{…}}`.
  A photo answer is published as an ntfy **attachment**: the JPEG encrypted
  with age to the guarded phone's keys (which include the remote's), named
  `<command id>.age`. The remote downloads and decrypts it.
- At most one photo every 5 seconds.
- Before a photo the guarded phone meters exposure on the centre of the frame;
  if the picture is still nearly black (mean luma < 16/255) it retakes it once
  after 1.5 s, and the answer carries `"dark":true` so the remote can say why
  (dark room, covered lens, camera access turned off in quick settings).

## Availability

The guarded phone listens only **while guarding** (it is then a foreground
service, which keeps network access in Doze); it reconnects by itself with
backoff. The remote listens while its Remote screen is open. ntfy.sh keeps
attachments for a few hours only, so an old photo answer may no longer be
downloadable.

## Code

- `data/remote/RemoteProtocol.kt`: wire format, pairing code, replay guard.
- `data/remote/NtfyClient.kt`: publish, attachment, JSON stream subscription.
- `data/remote/RemoteGuard.kt`: guarded-phone handler and listening loop
  (`MonitoringService` runs it while guarding).
- `data/remote/RemoteControlRepositoryImpl.kt`: pairing, settings, commands, answers.
- `data/remote/DeviceConfigTransfer.kt`: which settings travel, collect/apply.
- `age-encryption/remote.go`: `GenerateIdentity`, `RecipientOf`,
  `DecryptBytes`, used only on the remote phone.
