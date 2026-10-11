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
| `SOFA_OUTPUT_TOKEN` | Unset | Token for the [player outputs](#third-party-players) under `/output/*`, at least 32 letters, digits, hyphens or underscores; it goes in the URL, so it is separate from the API token. Unset, only the API token (as a bearer) opens them |
| `SOFA_XMLTV_DAYS` | `3` | Days ahead the XMLTV output carries, 1–14 |
| `SOFA_HDHR_ENABLED` | `true` | Serve the [HDHomeRun emulation](#plex-live-tv) (`/discover.json`, `/lineup.json`, `/auto/v*`) at the root |
| `SOFA_HDHR_TUNERS` | `2` | Tuners Plex sees, which is the number of HDHomeRun streams open at once, 1 to `SOFA_MAX_SESSIONS` |
| `SOFA_HDHR_DEVICE_ID` | Derived from the server identity | Eight hex digits Plex identifies the tuner by; keep it stable or Plex sees a new device |
| `SOFA_HDHR_NAME` | `Sofa King` | Friendly name Plex shows, 1–64 characters |
| `SOFA_HDHR_ALLOW` | Unset | Client IPs or CIDR blocks admitted to the HDHomeRun routes, comma separated (`192.168.1.10,192.168.1.0/24`); unset admits every address that reaches the port |

Relative paths resolve from the server's working directory. Configuration changes require a restart.

## Source settings

The source file is a JSON object with `providers` and `guides` (a bare JSON array is read as `providers`). Inside any string, `${NAME}` is replaced with the environment variable `NAME`; an unset variable fails startup. Keep tokenized paths in the environment, not in the file.

### Providers

Each provider object configures one M3U playlist, or with `"type": "native"` one built-in service client ([native providers](providers.md)):

| Field | Default | Description |
| --- | --- | --- |
| `id` | Required | Unique provider ID: 1–64 letters, digits, underscores or hyphens |
| `type` | `m3u` | `m3u` for a playlist, `native` for a service client |
| `playlistUrl` | Required for `m3u` | HTTP(S) M3U playlist URL; absent for `native` |
| `service` | Required for `native` | The service name, see [native providers](providers.md#configuration) |
| `options` | `{}` | Service options for `native`, for example `{"zip": "50309"}` for `pbs` |
| `guideUrl` | Unset | HTTP(S) XMLTV URL; an implicit guide with this provider's id. A native service with a guide of its own, or a published XMLTV, gets the implicit guide without it |
| `headers` | `{}` | Request headers for playlists, guides and streams (`m3u` only) |
| `refreshSeconds` | `900` (`3600` for `native`) | Import interval; minimum 60 seconds |
| `probe` | `true` | Enable automatic source qualification |
| `defaultProtocol` | Unset | `hls` or `mpegts` for entries without a `stream-type`; otherwise `.m3u8` implies HLS and other paths imply MPEG-TS. Applies until a probe or media fetch detects the protocol in the stream body |
| `allowEmpty` | `false` | Allow an empty playlist to withdraw all of this provider's sources |
| `renewableQuery` | Unset | Query parameter names (case-insensitive) that this provider rotates; replaces the [default list](#source-identity) for its stream URLs |

Keep provider IDs stable: they participate in source identity, and lineup entries refer to sources by id. Store credentials under the ignored `data/` directory or in the environment.

Failed imports keep the last complete snapshot. Removed sources become unavailable; lineup entries keep referring to them and skip them for playback until they return. With `allowEmpty: false`, an empty playlist also keeps the previous snapshot.

A malformed entry (no name; the same source key twice without a `stream-id`; a non-HTTP URL; an unsupported `stream-type`; a URL without `#EXTINF`) is skipped and counted, and the rest of the playlist imports. `/api/v1/providers` reports `skippedEntries`, `skipReasons` and `notes` (entries imported with a derived identity), and the server logs the counts without URLs. A playlist that is not extended M3U, exceeds an import limit, or skips every entry without `allowEmpty` fails the import and keeps the previous snapshot.

### Source identity

Every stream URL has a source key: the lowercase scheme and authority, the percent-decoded path, and the remaining query parameters sorted by name, after the renewable parameters are dropped. The default renewable names are `token`, `access_token`, `auth`, `sig`, `signature`, `expires`, `exp`, `key`, `hash`, `hmac` and `nonce`, compared case-insensitively; a provider's `renewableQuery` replaces that list for its URLs. Every other parameter carries identity, so `?id=1` and `?id=2` are two sources and `?pl=dlhd&token=A` and `?pl=dlhd&token=B` are one. A restream proxy whose path carries a percent-encoded upstream URL (`.../dlhd/https%3A%2F%2F...%3Fid%3D521?token=...`) keeps the whole upstream URL in the key, so two upstreams that differ anywhere inside it are two sources.

A channel's identity is its `channel-uuid`, else its `channel-id`, else its `tvg-id`, else the source key of its URL. An entry without a published identity is its own channel: the channel ID and source ID derive from the provider ID and the source key, the name, logo and group are display values that may change between refreshes, `legacyGuideId` is empty, and without `tvg-chno` the number is the first eight characters of the channel ID (renumber after adopting). A published identity repeated with another name or number keeps the first entry's name and number and adds the URL as an alternative source. An explicit `stream-id` replaces the source key in the source ID; the same source key twice under one channel is `duplicate-source` and the second URL is dropped.

Source IDs therefore survive token rotation, refreshes and reordering. Entries from before this rule referring to a source whose URL has an identity query parameter (anything outside the renewable list) show that source as `WITHDRAWN`; re-run the [Dispatcharr import](#migrate-from-dispatcharr), which replaces each entry's sources in place, or re-add the source through `PATCH /api/v1/admin/lineup/ENTRY_ID`. Sources whose URL has a lowercase scheme and host and no query, or only renewable parameters, keep their IDs.

A native provider's sources have `svc://SERVICE/CHANNEL_ID` URLs, stable identities that the server resolves to a playable URL when a session, a probe or the HDHomeRun remux needs one ([how a native source plays](providers.md#how-a-native-source-plays)). Its candidates' `tvg-id` is the service's channel id, which its implicit guide lists, so adding a candidate maps the guide.

### Guides

Each guide object configures one XMLTV source, independent of any provider:

| Field | Default | Description |
| --- | --- | --- |
| `id` | Required | Unique guide ID: 1–64 letters, digits, underscores or hyphens; distinct from provider IDs that have a `guideUrl` |
| `url` | Required | HTTP(S) XMLTV URL, plain or gzip (detected by content, not by extension) |
| `headers` | `{}` | Request headers for the guide |
| `refreshSeconds` | `21600` | Import interval; minimum 300 seconds |

Guides are parsed as a stream: a 100 MB guide needs no memory for the document, only for its channel index and the programmes it retains. The `<channel>` index (and every channel id seen on a programme) is stored for curation; programmes are kept only for channels the lineup maps onto that guide. Adding or changing a mapping triggers a refresh of that guide, so a newly mapped channel has programmes within one fetch. The download follows up to five redirects, is bounded by a 15-second socket inactivity timeout rather than a total request timeout, and is read as gzip whenever the body starts with the gzip magic bytes, whatever the final content type says. A `<!DOCTYPE tv SYSTEM "xmltv.dtd">` declaration is accepted; the DTD is never fetched, and an entity declared in the document fails the import instead of resolving. In UTF-8 and single-byte guides, control characters XML forbids (a raw backspace in a title, for example) are dropped before parsing and counted as `controlChars` in the import log line; a UTF-16 guide is parsed as sent. Each guide fails on its own: `/api/v1/admin/guides` reports `IMPORT_FAILED` for the failing one and the others keep refreshing, and the log line carries the exception class and its message with URLs redacted.

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

The XMLTV limit counts decompressed bytes as they stream past the parser, so it caps the download without buffering it; it is an order of magnitude above the largest national guides (around 100 MB), and a guide that crosses it fails with `IMPORT_FAILED` and `Guide exceeds size limit` in the log.

## Lineup

The app sees the lineup and nothing else: `/api/v1/channels`, `/api/v1/guide/query` and `/api/v1/playback-sessions` work on lineup entry IDs. Feed channels that are not in the lineup are candidates for curation.

A lineup entry has a stable UUID (the app's channel ID), a number, a name, an optional group and logo override, an enabled flag, an ordered list of sources from any providers (the failover order) and an optional guide mapping (guide ID plus XMLTV channel ID). Entries live in SQLite and survive imports and restarts. A disabled entry is kept but not served.

Playback walks an entry's sources in order within each health rank: healthy sources first, then unverified, then suspect, lineup order inside each rank. Quarantined, probation, archived, manually disabled and withdrawn sources are skipped. The channel's `logo` is the override or the first source's feed logo; `tags` carries the group; `legacyGuideId` carries the mapped XMLTV channel ID.

### Logos

The app, the M3U, the XMLTV and Plex see every logo at `http://SERVER_HOST:PORT/logos/KEY` on this server, not on the host the feed names ([API detail](api.md#logos)). A channel's logo is the entry's own, else the first of its sources' `tvg-logo` values, else the mapped guide channel's `<icon>`; a logo on a host listed in the curation settings' `retiredLogoHosts` counts as absent. The server fetches each lineup logo once after an import or lineup change, stores it under `SOFA_DATA_DIR/logos/`, serves it with a day of cache headers and an `ETag`, and refreshes it a week later; a refresh that fails keeps the old file, so retiring a logo host or a Dispatcharr instance leaves the logos in place. Only image content types up to 2 MiB are accepted, fetched under the [upstream address policy](#upstream-addresses) without provider headers. A `tvg-logo` or guide `<icon>` written with spaces or other unencoded characters is percent-encoded, and a relative one resolves against the playlist or guide URL; a reference that is no HTTP(S) URL after that counts as no logo. A logo the source never answered stays in the listings and answers `404`, which players show as their placeholder; the source is retried an hour later. Logos no longer named by any entry are deleted on the next sweep.

Curate with the [admin API](api.md#admin-api). The common steps:

1. Find candidates: `GET /api/v1/admin/candidates?q=espn` lists feed channels with their sources and which entries already use them.
2. Add one: `POST /api/v1/admin/lineup` with `{"channelId": "FEED_CHANNEL_ID"}` copies the candidate's number, name, logo, group and sources. When the candidate's `tvg-id` appears in a configured guide, that guide is mapped automatically (explicit guides first, then provider guides).
3. Add failover sources: `PATCH /api/v1/admin/lineup/ENTRY_ID` with `{"offerings": ["SOURCE_ID", "OTHER_SOURCE_ID"]}`.
4. Map the guide: find the XMLTV channel with `GET /api/v1/admin/guides/GUIDE_ID/channels?q=espn`, then `PATCH` with `{"guide": {"guideId": "GUIDE_ID", "channelId": "XMLTV_ID"}}`.
5. Renumber, rename or hide: `PATCH` with `number`, `name`, `group`, `logo` or `enabled`; `clearGroup`, `clearLogo` and `clearGuide` remove optional values. Reorder with `PUT /api/v1/admin/lineup/order` listing every entry ID once.

Everything from one provider at once: `POST /api/v1/admin/lineup/adopt` with `{"providerId": "whale"}` adds every channel of that provider whose sources no entry uses yet.

Every field one of these requests sets is pinned to the household. [Curation](curation.md) fills and corrects the rest by rule (groups, name and logo corrections, equivalent sources from other providers, entries disabled while no source works, candidates adopted by rule) and leaves pinned fields alone; `GET /api/v1/admin/curation/preview` shows what it would change before `settings.enabled` turns it on.

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

   Streams match sources by [source key](#source-identity), so renewed tokens do not matter and entries without a `tvg-id` match like any other; a stream whose identity query changed still matches when exactly one source shares its path. A stream whose path the provider no longer lists, as when FastChannels renamed its vidaa ids from `vidaa.11973` (`/play/vidaa/11973.m3u8`) to `vidaa.US:11973` (`/play/vidaa/US%3A11973.m3u8`), is reported unmatched; refresh the Dispatcharr M3U account or replace the entry's source after the import. EPG sources match configured guides by URL (query ignored), then by guide ID equal to the Dispatcharr source name. The report lists every EPG source that did not match and every entry with unmatched streams or an unmapped guide source. Channels whose streams all fail to match are skipped unless `--include-unmatched` is given; a channel without a number or name is skipped with an `error` and never aborts the rest. Household edits stored on Dispatcharr's channel override win over the synced values, and a channel hidden from output exports as disabled.

4. Map the EPG sources the report flags and apply:

   ```sh
   SOFA_ADMIN_TOKEN='TOKEN' python3 scripts/dispatcharr-import.py --file sofaking.json --guide-map 4=gracenote
   ```

   Entries keep Dispatcharr's channel uuid as their ID, so running the import again updates them in place. The mapped guides refresh and `GET /api/v1/channels` shows the lineup.

5. Delete `sofaking.json`. Point the app at this server and retire Dispatcharr's `sofaking` M3U/EPG output. If a pipeline curated Dispatcharr, move its rules over as in [cut over from the pipeline](curation.md#cut-over-from-the-pipeline).

## Third-party players

The lineup is also served as an M3U playlist with an XMLTV guide, for players that take a playlist URL ([API detail](api.md#player-outputs)). Set `SOFA_OUTPUT_TOKEN` (32+ characters, `openssl rand -hex 16`) and restart; the token goes in the URLs because players send no headers. Replace `SERVER_HOST:PORT` with the address the player reaches the server by (`OMV_HOST:9193` with the compose stack):

```text
http://SERVER_HOST:PORT/output/playlist.m3u?token=OUTPUT_TOKEN
http://SERVER_HOST:PORT/output/guide.xml?token=OUTPUT_TOKEN
```

The playlist names the guide URL (`url-tvg`), uses the lineup channel ID as `tvg-id` on both sides, and lists each channel's stream as `/output/stream/CHANNEL_ID.m3u8` (HLS source) or `.ts` (MPEG-TS source) with the token. Opening a stream URL opens a watch session, which ends when the player disconnects (TS) or 60 seconds after its last request (HLS). Players count against `SOFA_MAX_SESSIONS`; an idle player session gives way to the next channel change, and a busy server answers `429`.

TiviMate (Android TV):

1. Settings → Playlists → Add playlist → Enter URL. Paste the playlist URL above, Next.
2. Name the playlist and finish. TiviMate reads the `url-tvg` attribute and offers the guide URL as the EPG source; accept it. If it does not, Settings → EPG → Add source → Enter URL with the guide URL.
3. Settings → Playlists → the playlist → Update interval: set it to a few hours, so a changed stream suffix or a renumbered channel reaches the player. Channels match the guide by `tvg-id`, so no manual mapping is needed.
4. To change the token, update `SOFA_OUTPUT_TOKEN`, restart, and edit the playlist and EPG URLs in TiviMate.

VLC: Media → Open Network Stream with the playlist URL lists the channels; a single stream URL opens directly.

Verified against local fixtures with FFmpeg as the client (it follows the HLS redirect and decodes through the session, and a TS hang-up releases the session); TiviMate and VLC themselves are not verified.

## Plex Live TV

Plex Live TV & DVR takes an HDHomeRun tuner and an XMLTV guide. The server emulates the tuner at the root of its port ([API detail](api.md#hdhomerun-emulation)) and the guide is the [player output](#third-party-players) XMLTV, so Plex needs `SOFA_OUTPUT_TOKEN` for the guide URL and nothing for the tuner.

The tuner routes carry no token: anyone who can reach the port can list the lineup and watch through `/auto/vNUMBER`. Keep the port on the LAN and set `SOFA_HDHR_ALLOW` to the Plex server's address. Behind Docker on Linux, a published port keeps the client address with the default iptables setup; where Docker's userland proxy handles the port the server sees the Docker gateway address instead, and the allow-list must name that. Set `SOFA_HDHR_ENABLED=false` when nothing uses Plex.

1. Set `SOFA_HDHR_ALLOW=PLEX_SERVER_IP` (and `SOFA_HDHR_TUNERS` to the number of simultaneous Plex streams and recordings, within `SOFA_MAX_SESSIONS`), restart, and check `curl --fail http://SERVER_HOST:PORT/discover.json` from the Plex host.
2. In Plex Web: Settings → Live TV & DVR → Set Up Plex DVR (or "Add device" with a DVR already configured). Plex looks for tuners by SSDP broadcast, which this server does not answer, so choose "Don't see your HDHomeRun device? Enter its network address manually" and enter `SERVER_HOST:PORT` (`OMV_HOST:9193` with the compose stack). Plex reads `/discover.json` and `/lineup.json` and lists the device by `SOFA_HDHR_NAME` with the tuner count.
3. Plex shows the channels from `/lineup.json`; continue. At the guide step choose "Have an XMLTV guide on your server? Click here to use that instead" and enter `http://SERVER_HOST:PORT/output/guide.xml?token=OUTPUT_TOKEN`. Plex fetches it itself and refreshes it on its own schedule; keep `SOFA_XMLTV_DAYS` at 3 or more so a daily refresh never runs dry.
4. Map channels. The XMLTV lists each channel's name and number as display names and the lineup carries the same name and number, so Plex pairs most of them; pick the rest from the dropdowns. Finish.
5. Play a channel in Plex. Plex opens `/auto/vNUMBER`: an MPEG-TS source streams through the fan-out, an HLS source is remuxed to MPEG-TS by FFmpeg with stream copy, so CEA-608 captions inside the video reach Plex's caption setting. A busy tuner (every tuner or session slot taken) shows in Plex as "all tuners are in use".

After a lineup change (a new channel, a renumbering) open Settings → Live TV & DVR → the DVR → "Scan channels" in Plex, so it re-reads `/lineup.json`; the server answers the scan request at once with the curated lineup. Renumbering changes the stream URL, so Plex needs that re-scan before the channel plays again.

Verified against local fixtures: the Ktor client and FFmpeg read the HDHomeRun routes and a live HLS fixture remuxed to MPEG-TS, and the FFmpeg process is gone after the client hangs up. A Plex server is not verified.

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
| `429 CAPACITY_EXHAUSTED` | Every slot holds a watch session. Release unused sessions or wait for their 90-second leases to expire; previews and idle player sessions are evicted automatically. |
| A player gets `401` on the playlist URL | `SOFA_OUTPUT_TOKEN` is unset or the `token` query differs. The API token opens `/output/*` only as a bearer header. |
| TiviMate plays a channel as the wrong type after a probe | The stream suffix follows the stored protocol; refresh the playlist in the player. |
| A channel shows no logo | `curl -i http://SERVER_HOST:PORT/logos/KEY` with the key from the playlist: `404` means the source was refused (loopback or link-local address, a non-image content type, over 2 MiB, or an error), and the log has `logo fetch failed key=KEY class=... message="..."` saying why (the HTTP status, the content type, the size, or that the reference is no URL), with URLs redacted. The source is retried an hour later; `GET /api/v1/admin/lineup/ENTRY_ID` shows the source URL. |
| Plex does not find the tuner | Plex discovers by SSDP, which is not implemented: enter `SERVER_HOST:PORT` manually. `curl http://SERVER_HOST:PORT/discover.json` from the Plex host answers `403` when `SOFA_HDHR_ALLOW` leaves Plex out and `404` when `SOFA_HDHR_ENABLED=false`. |
| Plex says all tuners are in use | `SOFA_HDHR_TUNERS` streams are open, or every `SOFA_MAX_SESSIONS` slot is taken, or the channel has no eligible source; check `GET /api/v1/admin/lineup/ENTRY_ID`. |
| A Plex channel plays for a moment and stops | FFmpeg ended the remux; the server log has `remux ended exit=` with FFmpeg's last lines, URLs removed. |
| `503 SOURCE_UNAVAILABLE` | Inspect `GET /api/v1/admin/lineup/ENTRY_ID` for the entry's source states, or `/api/v1/sources` for quarantine, probation or manual disable. |
| A channel vanished from the lineup | `GET /api/v1/admin/lineup/ENTRY_ID`: `autoDisabledAt` set means curation disabled it (`autoDisabledReason` `unavailable` or `alias`); `GET /api/v1/admin/curation/changes` has the action. `PATCH` with `{"enabled": true}` takes the flag back for the household. |
| Curation changed a field the household wanted | `PATCH` the entry with the value, which pins the field; `pin` and `unpin` lists adjust pins without a value change. `GET /api/v1/admin/curation/preview` shows what the next run would do. |
| Sources never qualify | Check FFmpeg availability, `probe`, and whether the feed has credential/custom headers. |
