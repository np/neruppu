# HTTPS webhook transport

A fourth alert channel, next to Telegram and Matrix: each alert is one
`POST` to an HTTPS URL, built from a request template you define, so it can
talk directly to services such as Pushover or ntfy, or to your own server.

Settings → Integrations → **HTTPS Webhook**. Like the other channels it is
exclusive: saving it clears Telegram and Matrix, and vice versa. Everything is
stored in `EncryptedSharedPreferences`, including tokens in headers or fields.

## Template

| Setting | |
|---|---|
| Endpoint URL | static `https://…` URL (no placeholders, no `http`, no user-info, no fragment) |
| Body encoding | `multipart` (`multipart/form-data`), `form` (`application/x-www-form-urlencoded`) or `JSON` (flat object of strings) |
| Headers | one `Name: value` per line; `Content-Type`, `Content-Length`, `Host`, `Transfer-Encoding` are set automatically |
| Fields | one `name=value` per line (at least one); everything after the first `=` is the value |
| Capture field | multipart only: name of the part carrying the capture; **empty = captures are not sent** |

Blank lines and lines starting with `#` are ignored. Values are substituted
as plain text and then encoded for the chosen body type, so they never need
escaping.

### Placeholders

| Placeholder | Value |
|---|---|
| `{message}` | caption; for an encrypted capture only `[Neruppu] Alert at <time>` |
| `{details}` | full event description, **including the sensor type** — reveals what happened to whoever receives it |
| `{kind}` | `test` (Settings → Test), `text` (alert without media), `file` (alert for a capture) |
| `{time}` | event time, `yyyy-MM-dd HH:mm:ss`, device time zone |
| `{timestamp_s}` / `{timestamp_ms}` | event time, Unix seconds / milliseconds |
| `{filename}` | `<timestamp_ms>.age` when the capture is attached, empty otherwise |

Unknown placeholders are refused when saving, so a typo cannot silently go out.

### Captures

When attached, an encrypted capture goes out untouched as
`<timestamp_ms>.age`, `application/octet-stream`: neither the name nor
`{message}` tells a photo from a sound clip. Decrypt it with
`age -d -i key.txt` or `neruppu-decrypt`.

Without a capture field the capture **stays on the device** after a
successful send (the alert is marked uploaded, the file is not deleted), since
it is then the only copy. Clear it from the Events tab.

## Response

Any `2xx` is success. Anything else, including redirects (not followed for
`POST`), marks the upload failed and shows the status plus the start of the
response body (e.g. Pushover's error message); WorkManager retries later, so
an endpoint may receive the same alert more than once.

## Presets

"Start from" fills the form; nothing is stored until **Save**.

**Pushover** (`https://api.pushover.net/1/messages.json`, form): fields
`token`, `user`, `title`, `message={message}`, `timestamp={timestamp_s}`,
`priority=1`. Replace `YOUR_APP_TOKEN` / `YOUR_USER_KEY`. No capture field:
Pushover attachments must be images, and an `.age` blob is not one — the
notification tells you *when*; the capture stays encrypted on the phone.

**ntfy** (`https://ntfy.sh/`, JSON): fields `topic`, `title`,
`message={message}`, `priority=4`. For a protected topic or your own server,
uncomment the `Authorization` header and change the URL.

**Neruppu receiver** (multipart): `kind`, `timestamp={timestamp_ms}`,
`message`, capture field `file`, bearer token header. Matches
`tools/neruppu-receiver`.

## Reference receiver

`tools/neruppu-receiver` (Python stdlib only) stores each capture and appends
one JSON line per alert to `alerts.jsonl`:

```sh
nix run .#neruppu-receiver -- --port 8080 --out ./alerts --token "$TOKEN"
```

It listens on plain HTTP on `127.0.0.1`: expose it through a TLS-terminating
reverse proxy (Caddy, nginx, a tunnel…). Check it without the app:

```sh
curl -H "Authorization: Bearer $TOKEN" -F kind=test -F timestamp=0 \
     -F message=hello https://your.host/neruppu
```
