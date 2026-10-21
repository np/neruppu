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
2. Guarded phone: Settings → Remote control → *Scan the remote's code*.
   It stores the channel and **adds the remote's age public key** to
   Settings → Encryption, so its captures can be opened by the remote.

Anyone who sees the code can command the guarded phone: show it only while
pairing. A phone paired as remote refuses to become a guarded phone, and its
Home tab is replaced by the Remote screen: a phone holding an age private key
never guards. Unpair in the same dialog on each phone.

## Protocol

- Commands go to `<topic>-c`, answers to `<topic>-r`.
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
- `data/remote/RemoteControlRepositoryImpl.kt`: pairing, commands, answers.
- `age-encryption/remote.go`: `GenerateIdentity`, `RecipientOf`,
  `DecryptBytes`, used only on the remote phone.
