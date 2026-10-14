# HTTPS webhook transport

A fourth alert channel, next to Telegram and Matrix: each alert is one
`POST` to an HTTPS URL you control. No account, no third-party service.

Settings → Integrations → **HTTPS Webhook**:

| Field | |
|---|---|
| Endpoint URL | must be `https://…` (plain `http`, user-info and fragments are refused) |
| Bearer token | optional; sent as `Authorization: Bearer <token>` |

Like the other channels it is exclusive: saving it clears Telegram and
Matrix, and saving either of those clears it. Settings are stored in
`EncryptedSharedPreferences`.

## Request

```
POST <endpoint URL>
Authorization: Bearer <token>          (only if a token is set)
User-Agent: Neruppu
Content-Type: multipart/form-data; boundary=…
```

| Part | Value |
|---|---|
| `kind` | `test` (Settings → Test connection), `text` (alert without media), `file` (capture attached) |
| `timestamp` | event time, Unix epoch **milliseconds** |
| `message` | caption; for `file` with an encrypted capture it is only `[Neruppu] Alert at <time>` |
| `file` | only for `kind=file`: the capture bytes, untouched |

For an encrypted capture (always the case when monitoring, since it requires
an age key) the `file` part is named `<timestamp>.age` with type
`application/octet-stream`: neither the name nor the caption reveals whether
it is a photo or a sound clip. Decrypt with `age -d -i key.txt` or
`neruppu-decrypt`; the result is a JPEG or an MP4 (AAC audio).

## Response

Any `2xx` is success. Anything else, including redirects (they are not
followed for `POST`), marks the upload **failed**; the event stays queued and
WorkManager retries it later — so the endpoint can receive the same capture
more than once and should not overwrite files blindly.

## Reference receiver

`tools/neruppu-receiver` (Python stdlib only) stores each file and appends one
JSON line per alert to `alerts.jsonl`:

```sh
nix run .#neruppu-receiver -- --port 8080 --out ./alerts --token "$TOKEN"
# or: NERUPPU_TOKEN=… python3 tools/neruppu-receiver --port 8080
```

It listens on plain HTTP on `127.0.0.1`: expose it through a TLS-terminating
reverse proxy (Caddy, nginx, a tunnel…). Quick check without the app:

```sh
curl -H "Authorization: Bearer $TOKEN" -F kind=test -F timestamp=0 \
     -F message=hello https://your.host/neruppu
```
