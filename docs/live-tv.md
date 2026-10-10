# Configuration

## Server settings

Set these environment variables before starting the server:

| Variable | Default | Description |
| --- | --- | --- |
| `SOFA_HOST` | `127.0.0.1` | Listen address |
| `SOFA_PORT` | `8080` | Listen port, 1–65535 |
| `SOFA_DATA_DIR` | `data` | Database directory |
| `SOFA_SOURCES` | Unset | Path to the source configuration JSON file; unset starts an empty catalog |
| `SOFA_API_TOKEN` | Unset | API bearer token, at least 32 characters; required for non-loopback binding |
| `SOFA_MAX_SESSIONS` | `4` | Maximum active playback sessions, 1–16 |
| `SOFA_FFMPEG` | `ffmpeg` | FFmpeg executable used by source probes |
| `SOFA_PROBE_PARALLELISM` | `6` | Concurrent source probes, 1–32 |
| `SOFA_RESTREAM_ORIGINS` | Unset | Comma-separated `host[:port]` list of LAN restream proxies whose 502 counts as source evidence, for example `192.168.1.3:3000` |

Relative paths resolve from the server's working directory. Configuration changes require a restart.

## Source settings

The source file is a JSON array. Each object configures one provider:

| Field | Default | Description |
| --- | --- | --- |
| `id` | Required | Unique provider ID: 1–64 letters, digits, underscores or hyphens |
| `playlistUrl` | Required | HTTP(S) M3U playlist URL |
| `guideUrl` | Unset | HTTP(S) XMLTV guide URL |
| `headers` | `{}` | Request headers for playlists, guides and streams |
| `refreshSeconds` | `900` | Import interval; minimum 60 seconds |
| `probe` | `true` | Enable automatic source qualification |
| `defaultProtocol` | Unset | `hls` or `mpegts`; otherwise `.m3u8` implies HLS and other paths imply MPEG-TS |
| `allowEmpty` | `false` | Allow an empty playlist to withdraw all of this provider's sources |

Keep provider IDs stable: they participate in channel and source identity. Store credentials under the ignored `data/` directory.

Failed imports keep the last complete snapshot. Removed sources become unavailable; their channel IDs remain in the catalog. With `allowEmpty: false`, an empty playlist also keeps the previous snapshot.

### Import limits

| Input | Limit |
| --- | --- |
| Source configuration | 1 MiB |
| Playlist | 16 MiB |
| XMLTV | 64 MiB |
| Channels per import | 10,000 |
| Sources per import | 20,000 |
| Programs per import | 250,000 |

## LAN access

Set a token and bind to the LAN address. Replace `TOKEN` with a secret of at least 32 characters:

```sh
export SOFA_API_TOKEN='TOKEN'
SOFA_HOST=0.0.0.0 SOFA_SOURCES=./data/sources.json \
  server/build/install/server/bin/server
```

Send the token with every `/api/` request:

```sh
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" \
  http://SERVER_HOST:8080/api/v1/channels
```

The token permits both playback and source administration. Health endpoints are public. Media URLs authorize playback by possession and expire with the session. Use HTTPS when sending the token over an untrusted connection.

## Provider headers

Credentials are sent only to the configured request origin. Cross-origin CDN requests receive only `User-Agent`, `Accept`, `Accept-Language`, `Origin` and `Referer`; authorization, cookies and custom headers are removed. Redirects are limited to five. HTTPS redirects cannot downgrade to HTTP; sources configured with HTTPS also reject HTTP media resources.

Providers that need secret headers on a different CDN origin are unsupported. Direct FFmpeg probes skip feeds with credential or custom headers. See [source health](source-health.md).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Empty catalog | Set `SOFA_SOURCES`; inspect `/api/v1/providers` for `IMPORT_FAILED`; check playlist URL and format. |
| Import stays unchanged | A failed or empty import preserves the previous snapshot. Check provider status and `allowEmpty`. |
| Channel has no guide | Match M3U `tvg-id` to XMLTV `channel`; supply valid timestamps with offsets and positive durations. |
| Extensionless HLS answers `410 STREAM_CHANGED` once | The first fetch detects HLS and bumps the epoch; fetch the session again. Set `defaultProtocol` to `hls`, or `stream-type="hls"` on the entry, to avoid that round trip. |
| Startup rejects LAN binding | Set `SOFA_API_TOKEN` to at least 32 characters. |
| `401 AUTH_REQUIRED` | Send the configured token in the Authorization header. |
| `429 CAPACITY_EXHAUSTED` | Release unused sessions or wait for their 90-second leases to expire. |
| `503 SOURCE_UNAVAILABLE` | Inspect `/api/v1/sources` for quarantine, probation or manual disable. |
| Sources never qualify | Check FFmpeg availability, `probe`, and whether the feed has credential/custom headers. |
