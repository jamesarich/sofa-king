# Configuration

## Server settings

Set these environment variables before starting the server:

| Variable | Default | Description |
| --- | --- | --- |
| `SOFA_HOST` | `127.0.0.1` | Listen address |
| `SOFA_PORT` | `8080` | Listen port, 1–65535 |
| `SOFA_DATA_DIR` | `data` | Database directory |
| `SOFA_SOURCES` | Unset | Path to the source configuration JSON file; unset starts with no providers or guides |
| `SOFA_API_TOKEN` | Unset | API bearer token, at least 32 characters; required for non-loopback binding |
| `SOFA_ADMIN_TOKEN` | Unset | Bearer token for `/api/v1/admin/*`, at least 32 characters; unset means the API token administers the lineup |
| `SOFA_MAX_SESSIONS` | `4` | Maximum active playback sessions, 1–16 |
| `SOFA_FFMPEG` | `ffmpeg` | FFmpeg executable used by source probes |
| `SOFA_PROBE_PARALLELISM` | `6` | Concurrent source probes, 1–32 |
| `SOFA_RESTREAM_ORIGINS` | Unset | Comma-separated `host[:port]` list of LAN restream proxies whose 502 counts as source evidence, for example `192.168.1.3:3000` |
| `SOFA_OBSERVATION_RETENTION_DAYS` | `7` | Days of health observation history to keep, 1–3650 |
| `SOFA_ALLOW_LOOPBACK_UPSTREAMS` | `false` | Allow playlist, guide and stream URLs on loopback (`127.0.0.1`, `::1`, `localhost`); for local fixtures only |

Relative paths resolve from the server's working directory. Configuration changes require a restart.

## Source settings

The source file is a JSON object with `providers` and `guides` (a bare JSON array is read as `providers`). Inside any string, `${NAME}` is replaced with the environment variable `NAME`; an unset variable fails startup. Keep tokenized paths in the environment, not in the file.

### Providers

Each provider object configures one M3U playlist:

| Field | Default | Description |
| --- | --- | --- |
| `id` | Required | Unique provider ID: 1–64 letters, digits, underscores or hyphens |
| `playlistUrl` | Required | HTTP(S) M3U playlist URL |
| `guideUrl` | Unset | HTTP(S) XMLTV URL; an implicit guide with this provider's id |
| `headers` | `{}` | Request headers for playlists, guides and streams |
| `refreshSeconds` | `900` | Import interval; minimum 60 seconds |
| `probe` | `true` | Enable automatic source qualification |
| `defaultProtocol` | Unset | `hls` or `mpegts` for entries without a `stream-type`; otherwise `.m3u8` implies HLS and other paths imply MPEG-TS. Applies until a probe or media fetch detects the protocol in the stream body |
| `allowEmpty` | `false` | Allow an empty playlist to withdraw all of this provider's sources |

Keep provider IDs stable: they participate in source identity, and lineup entries refer to sources by id. Store credentials under the ignored `data/` directory or in the environment.

Failed imports keep the last complete snapshot. Removed sources become unavailable; lineup entries keep referring to them and skip them for playback until they return. With `allowEmpty: false`, an empty playlist also keeps the previous snapshot.

A malformed entry (no `tvg-id`, `channel-id` or `channel-uuid`; an identity reused with a different name or number; the same source differing only by query string without a `stream-id`; a non-HTTP URL; an unsupported `stream-type`; a URL without `#EXTINF`) is skipped and counted, and the rest of the playlist imports. `/api/v1/providers` reports `skippedEntries` and `skipReasons`, and the server logs the counts at warn level without URLs. A playlist that is not extended M3U, exceeds an import limit, or skips every entry without `allowEmpty` fails the import and keeps the previous snapshot.

### Guides

Each guide object configures one XMLTV source, independent of any provider:

| Field | Default | Description |
| --- | --- | --- |
| `id` | Required | Unique guide ID: 1–64 letters, digits, underscores or hyphens; distinct from provider IDs that have a `guideUrl` |
| `url` | Required | HTTP(S) XMLTV URL, plain or gzip (detected by content, not by extension) |
| `headers` | `{}` | Request headers for the guide |
| `refreshSeconds` | `21600` | Import interval; minimum 300 seconds |

Guides are parsed as a stream, so a guide of tens of megabytes needs no memory for the whole document. The `<channel>` index (and every channel id seen on a programme) is stored for curation; programmes are kept only for channels the lineup maps onto that guide. Adding or changing a mapping triggers a refresh of that guide, so a newly mapped channel has programmes within one fetch. Each guide fails on its own: `/api/v1/admin/guides` reports `IMPORT_FAILED` for the failing one and the others keep refreshing.

### Import limits

| Input | Limit |
| --- | --- |
| Source configuration | 1 MiB |
| Playlist | 16 MiB |
| XMLTV, decompressed | 1 GiB |
| Channels per provider | 10,000 |
| Sources per provider | 20,000 |
| Channels per guide | 100,000 |
| Retained programmes per guide | 1,000,000 |

## Lineup

The app sees the lineup and nothing else: `/api/v1/channels`, `/api/v1/guide/query` and `/api/v1/playback-sessions` work on lineup entry IDs. Feed channels that are not in the lineup are candidates for curation.

A lineup entry has a stable UUID (the app's channel ID), a number, a name, an optional group and logo override, an enabled flag, an ordered list of sources from any providers (the failover order) and an optional guide mapping (guide ID plus XMLTV channel ID). Entries live in SQLite and survive imports and restarts. A disabled entry is kept but not served.

Playback walks an entry's sources in order within each health rank: healthy sources first, then unverified, then suspect, lineup order inside each rank. Quarantined, probation, archived, manually disabled and withdrawn sources are skipped. The channel's `logo` is the override or the first source's feed logo; `tags` carries the group; `legacyGuideId` carries the mapped XMLTV channel ID.

Curate with the [admin API](api.md#admin-api). The common steps:

1. Find candidates: `GET /api/v1/admin/candidates?q=espn` lists feed channels with their sources and which entries already use them.
2. Add one: `POST /api/v1/admin/lineup` with `{"channelId": "FEED_CHANNEL_ID"}` copies the candidate's number, name, logo, group and sources. When the candidate's `tvg-id` appears in a configured guide, that guide is mapped automatically (explicit guides first, then provider guides).
3. Add failover sources: `PATCH /api/v1/admin/lineup/ENTRY_ID` with `{"offerings": ["SOURCE_ID", "OTHER_SOURCE_ID"]}`.
4. Map the guide: find the XMLTV channel with `GET /api/v1/admin/guides/GUIDE_ID/channels?q=espn`, then `PATCH` with `{"guide": {"guideId": "GUIDE_ID", "channelId": "XMLTV_ID"}}`.
5. Renumber, rename or hide: `PATCH` with `number`, `name`, `group`, `logo` or `enabled`; `clearGroup`, `clearLogo` and `clearGuide` remove optional values. Reorder with `PUT /api/v1/admin/lineup/order` listing every entry ID once.

Everything from one provider at once: `POST /api/v1/admin/lineup/adopt` with `{"providerId": "fastchannels"}` adds every channel of that provider whose sources no entry uses yet.

## Migrate from Dispatcharr

The export runs inside the Dispatcharr container and reads its database only. It writes a `sofa-dispatcharr-export/1` JSON file with, per channel: Dispatcharr uuid, number, name, group, logo URL, `tvg-id`, EPG source id and XMLTV channel id, enabled flag, and the ordered stream URLs with their M3U account names. **The file contains tokenized stream URLs. Keep it out of version control and delete it after the import.**

1. Configure this server with the same inputs Dispatcharr uses: each M3U account as a provider and each XMLTV source as a guide, as in the [README](../README.md#run). Start it and wait until `/api/v1/providers` and `/api/v1/admin/guides` show `lastSuccessAt` for every source.
2. Export the profile (replace `dispatcharr` with the container name and `sofaking` with the profile):

   ```sh
   docker exec -i -e SOFA_PROFILE=sofaking -e SOFA_OUT=/tmp/sofaking.json \
     dispatcharr python manage.py shell < scripts/dispatcharr-export.py
   docker cp dispatcharr:/tmp/sofaking.json ./sofaking.json
   docker exec dispatcharr rm /tmp/sofaking.json
   ```

   Standard output shows counts only.

3. Preview the import:

   ```sh
   SOFA_ADMIN_TOKEN='TOKEN' python3 scripts/dispatcharr-import.py --file sofaking.json --dry-run
   ```

   Streams match sources by URL with the query string ignored, so renewed tokens do not matter. EPG sources match configured guides by URL (query ignored), then by guide ID equal to the Dispatcharr source name. The report lists every EPG source that did not match and every entry with unmatched streams or an unmapped guide source. Channels whose streams all fail to match are skipped unless `--include-unmatched` is given; a channel without a number or name is skipped with an `error` and never aborts the rest. Household edits stored on Dispatcharr's channel override win over the synced values, and a channel hidden from output exports as disabled.

4. Map the EPG sources the report flags and apply:

   ```sh
   SOFA_ADMIN_TOKEN='TOKEN' python3 scripts/dispatcharr-import.py --file sofaking.json --guide-map 4=gracenote
   ```

   Entries keep Dispatcharr's channel uuid as their ID, so running the import again updates them in place. The mapped guides refresh and `GET /api/v1/channels` shows the lineup.

5. Delete `sofaking.json`. Point the app at this server and retire Dispatcharr's `sofaking` M3U/EPG output.

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

The API token permits playback and source administration, and the lineup admin API unless `SOFA_ADMIN_TOKEN` is set, in which case only the admin token opens `/api/v1/admin/*` and the API token is refused there. Health endpoints are public. Media URLs authorize playback by possession and expire with the session. Use HTTPS when sending a token over an untrusted connection.

## Provider headers

Credentials are sent only to the configured request origin. Cross-origin CDN requests receive only `User-Agent`, `Accept`, `Accept-Language`, `Origin` and `Referer`; authorization, cookies and custom headers are removed. Redirects are limited to five. HTTPS redirects cannot downgrade to HTTP; sources configured with HTTPS also reject HTTP media resources.

Providers that need secret headers on a different CDN origin are unsupported. Direct FFmpeg probes skip feeds with credential or custom headers. See [source health](source-health.md).

## Upstream addresses

Playlist, guide, probe and media requests go only to `http`/`https` URLs without user info. Loopback (`127.0.0.0/8`, `::1`, `localhost`), link-local (`169.254.0.0/16`, `fe80::/10`), unspecified (`0.0.0.0`, `::`) and multicast literals are refused, including as redirect targets; RFC1918 LAN hosts such as `192.168.1.3` are allowed. Hostnames are not resolved before the request. A refused media address answers `502 UPSTREAM_REJECTED`, a refused feed or guide address fails that import, and a refused probe target is offering evidence. `SOFA_ALLOW_LOOPBACK_UPSTREAMS=true` permits loopback for local fixtures.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `/api/v1/channels` is empty | The lineup is empty or every entry is disabled. Adopt a provider or import from Dispatcharr; `GET /api/v1/admin/lineup` shows the entries. |
| No candidates | Set `SOFA_SOURCES`; inspect `/api/v1/providers` for `IMPORT_FAILED`; check playlist URL and format. |
| Import stays unchanged | A failed or empty import preserves the previous snapshot. Check provider status and `allowEmpty`. |
| Channel has no guide | Check the entry's `guide` mapping against `GET /api/v1/admin/guides/GUIDE_ID/channels`; a mapping changed moments ago is filled on the next guide fetch. `IMPORT_FAILED` on `/api/v1/admin/guides` means that guide's fetch or parse failed. |
| Entry source shows `WITHDRAWN` | The provider no longer lists that stream. The entry keeps the reference; replace it when the provider offers a new URL. |
| Extensionless HLS answers `410 STREAM_CHANGED` once | The source has not been probed yet and the first fetch detects HLS, stores it and bumps the epoch; fetch the session again. The source keeps the detected protocol across restarts. Set `defaultProtocol` to `hls`, or `stream-type="hls"` on the entry, so unprobed sources start right. |
| Startup rejects LAN binding | Set `SOFA_API_TOKEN` to at least 32 characters. |
| `401 AUTH_REQUIRED` | Send the configured token in the Authorization header; admin routes need the admin token when one is configured. |
| `429 CAPACITY_EXHAUSTED` | Every slot holds a watch session. Release unused sessions or wait for their 90-second leases to expire; previews are evicted automatically. |
| `503 SOURCE_UNAVAILABLE` | Inspect `GET /api/v1/admin/lineup/ENTRY_ID` for the entry's source states, or `/api/v1/sources` for quarantine, probation or manual disable. |
| Sources never qualify | Check FFmpeg availability, `probe`, and whether the feed has credential/custom headers. |
