# Live TV: configuration, lineup and sources

This page is the configuration reference for the server: every environment variable, the source file that lists providers and guides, how sources and channels are identified, the lineup, the player outputs and Plex. To run the server on the LAN without Docker, see [development](development.md#run-without-docker); the tokens each route takes are in [security](security.md#credentials-and-the-routes-they-open).

## Server settings

Set these environment variables before starting the server. Relative paths resolve from the server's working directory. Configuration changes require a restart. The [Docker image](deploy.md) sets `SOFA_HOST=0.0.0.0`, `SOFA_PORT=8080` and `SOFA_DATA_DIR=/config`. `deploy/compose.yaml` publishes container port 8080 as host port 9193 and sets `SOFA_HDHR_ENABLED=false` unless `deploy/.env` sets it.

| Variable | Default | Description |
| --- | --- | --- |
| `SOFA_HOST` | `127.0.0.1` | Listen address |
| `SOFA_PORT` | `8080` | Listen port, 1-65535 |
| `SOFA_DATA_DIR` | `data` | Directory for the database, the logo cache and `secrets.json`, and where `sources.json` and `policy.json` are looked for |
| `SOFA_RECORDINGS_DIR` | `SOFA_DATA_DIR/recordings`; `/recordings` in `deploy/compose.yaml` | Folder the [recordings](recordings.md) are kept in, laid out as [the recordings folder](deploy.md#the-recordings-folder) describes. |
| `SOFA_SOURCES` | `SOFA_DATA_DIR/sources.json` when it exists | Path to the source configuration JSON file; with neither, the server starts with no providers or guides |
| `SOFA_POLICY` | `SOFA_DATA_DIR/policy.json` when it exists | Path to a [lineup policy](lineup-policy.md) JSON file in a writable directory, loaded and validated at start (any error stops the server). With a policy, the lineup is built from it |
| `SOFA_API_TOKEN` | Generated on a non-loopback bind | API bearer token, at least 32 characters. On a non-loopback bind, an unset token is generated at the first start, stored in `SOFA_DATA_DIR/secrets.json` and reused; the admin portal's Settings page reveals it. On loopback, unset leaves the API open |
| `SOFA_ADMIN_TOKEN` | Generated on a non-loopback bind | Bearer token for `/api/v1/admin/*` and the admin portal, at least 32 characters. On a non-loopback bind, an unset token is generated and stored like the API token; the log says where, never the token. On loopback, unset means the API token administers the lineup too |
| `SOFA_API_TOKEN_FILE`, `SOFA_ADMIN_TOKEN_FILE`, `SOFA_OUTPUT_TOKEN_FILE`, `SOFA_PLEX_TOKEN_FILE`, `SOFA_TMDB_TOKEN_FILE` | Unset | A file holding the token, for Docker secrets. The value is read at start without its trailing line break and validated like the variable. Setting a variable and its `_FILE` variant, or naming an unreadable file, stops the start |
| `SOFA_MAX_SESSIONS` | `4` | Maximum active playback sessions, 1-16. A value saved in the portal's [runtime settings](admin.md#settings) takes precedence |
| `SOFA_RECORDINGS_FREE_GIB` | `10` | Free space, in GiB, the DVR keeps on the recordings folder's disk by deleting the oldest recordings, 0-100000; see [cleanup](recordings.md#cleanup). A value saved in the portal's [runtime settings](admin.md#settings) takes precedence |
| `SOFA_RECORDINGS_KEEP_DAYS` | `0` | Days the DVR keeps a recording, 0-3650; `0` keeps it until space runs short. A value saved in the portal's [runtime settings](admin.md#settings) takes precedence |
| `SOFA_COMSKIP` | `comskip` | Comskip executable that finds [commercial breaks](recordings.md#commercial-breaks) in finished recordings; `none` turns the scan off. The image installs it |
| `SOFA_FFMPEG` | `ffmpeg` | FFmpeg executable used by source probes and the HDHomeRun remux; FFprobe is looked for beside it, or on the path when this is a bare name |
| `SOFA_MEDIA_DIR` | `SOFA_DATA_DIR/media` | The folder [media libraries](libraries.md) are added from. The server only reads it; mount it read only |
| `SOFA_TMDB_TOKEN` | Unset | A [TMDB](https://www.themoviedb.org/settings/api) API read access token or v3 API key, at least 32 characters. With it, library scans [look up details and artwork](libraries.md#details-from-tmdb) for movies and shows |
| `SOFA_MAX_TRANSCODES` | `2` | Library files FFmpeg converts or remuxes at once for [library playback](api.md#play-library-files), 1-8. Direct play is not counted |
| `SOFA_HWACCEL` | `none` | The GPU encoder library files are converted with: `vaapi` (Intel or AMD), `qsv` (Intel Quick Sync) or `nvenc` (NVIDIA); `none` converts in software with libx264. See [hardware transcoding](libraries.md#hardware-transcoding) |
| `SOFA_HWACCEL_DEVICE` | `/dev/dri/renderD128` | The render node `vaapi` and `qsv` open |
| `SOFA_TMDB_LANGUAGE` | `en-US` | The language TMDB answers in, such as `de` or `pt-BR`; its region picks the content rating |
| `SOFA_PROBE_PARALLELISM` | `6` | Concurrent source probes, 1-32 |
| `SOFA_RESTREAM_ORIGINS` | Unset | Comma-separated `host[:port]` list of LAN restream proxies whose 502 counts as source evidence, for example `RESTREAM_HOST:3000` |
| `SOFA_OBSERVATION_RETENTION_DAYS` | `7` | Days of health observation history to keep, 1-3650 |
| `SOFA_ALLOW_LOOPBACK_UPSTREAMS` | `false` | Allow playlist, guide and stream URLs on loopback (`127.0.0.1`, `::1`, `localhost`); for local fixtures only |
| `SOFA_OUTPUT_TOKEN` | Unset | Token for the [player outputs](#third-party-players) under `/output/*`, at least 32 letters, digits, hyphens or underscores. It goes in the URL, so it is separate from the API token. Unset, only the API token (as a bearer) opens them |
| `SOFA_XMLTV_DAYS` | `3` | Days ahead the XMLTV output carries, 1-14. A value saved in the portal's [runtime settings](admin.md#settings) takes precedence |
| `SOFA_HDHR_ENABLED` | `true`; `false` in `deploy/compose.yaml` | Serve the [HDHomeRun emulation](#plex-live-tv) (`/discover.json`, `/lineup.json`, `/auto/v*`) at the root. The server binary defaults to `true`. The Compose file passes `false` unless `deploy/.env` sets `SOFA_HDHR_ENABLED=true` |
| `SOFA_HDHR_TUNERS` | `2` | Tuners each device exposes, which is the number of HDHomeRun streams open at once on that device, 1 to `SOFA_MAX_SESSIONS`. A value saved in the portal's [runtime settings](admin.md#settings) takes precedence |
| `SOFA_HDHR_DEVICE_ID` | Derived from the server identity | Eight hex digits Plex identifies the tuner by; keep it stable or Plex sees a new device |
| `SOFA_HDHR_NAME` | `Sofa King` | Friendly name Plex shows, 1-64 characters without control characters |
| `SOFA_HDHR_ALLOW` | Unset | Client IPs or CIDR blocks admitted to the HDHomeRun routes, comma separated (for example `PLEX_SERVER_IP` or `LAN_CIDR`). Required when `SOFA_HOST` is not loopback and HDHomeRun is enabled: the server refuses to start without it. On loopback, unset admits every address that reaches the port |
| `SOFA_HDHR_EXTRA_PORTS` | Unset | Further ports the server listens on, comma separated, up to 7. Each is one more HDHomeRun device (`SOFA_HDHR_NAME 2`, and so on) carrying its share of the lineup. See [several devices](#several-devices-for-a-large-lineup) |
| `SOFA_HDHR_DEVICE_SIZE` | `250` | With a lineup policy, channels per HDHomeRun device; devices hold consecutive ranges of the lineup. 1 to 5000 |
| `SOFA_HDHR_PUBLIC_PORTS` | `SOFA_PORT`, then `SOFA_HDHR_EXTRA_PORTS` | The host ports Plex reaches each device on, comma separated, one per device in device order. Set it when Docker publishes the ports under other numbers |
| `SOFA_PLEX_URL` | Unset | The Plex server to [keep in step](#keep-plex-in-step) with the lineup, as `http(s)://HOST[:PORT]` with no path, query or credentials (for example `http://host.docker.internal:32400`). Set it with `SOFA_PLEX_TOKEN`; requires `SOFA_HDHR_ENABLED` |
| `SOFA_PLEX_TOKEN` | Unset | The Plex server's `X-Plex-Token`, at least 8 characters without spaces, control characters or DEL. A secret: it is sent only as a request header and never logged |
| `SOFA_PLEX_SYNC_MINUTES` | `60` | Minutes between Plex syncs when the lineup does not change, 5 to 1440. A value saved in the portal's [runtime settings](admin.md#settings) takes precedence |
| `SOFA_PLEX_TUNER_BASE` | Unset | Scheme and host Plex reaches this server's tuners on, as `http(s)://HOST` with no port, path, query or credentials, for example `http://192.168.1.20`. When set, a sync adds devices Plex lacks |
| `SOFA_LOG_LEVEL` | `info` | Log level on stderr: `trace`, `debug`, `info`, `warn`, `error` or `off`; the Events page keeps INFO and above whatever it is. A value saved in the portal's [runtime settings](admin.md#settings) takes precedence |
| `SOFA_LOG_FORMAT` | `text` | `text` for one line per event, or `json` for one JSON object per line with the level, logger, message and request id. Logs go to stderr. See [logs](operations.md#logs) |
| `SOFA_CORS_ORIGINS` | Unset | Comma-separated browser origins, such as `https://tv.example.com,http://192.168.1.20:8080`, that may call the API from a page on another origin, for example the Sofa King web app served elsewhere. Each is `scheme://host[:port]` with no path; `*` is not accepted. Unset sends no CORS headers, so only pages on the server's own origin can use the API from a browser. Tokens still authorize every call; cookies never cross origins |
| `SOFA_REVISION` | `unknown`; the image sets it | The commit the server was built from, reported by `GET /api/v1/admin/status` and the admin portal footer. The image sets it from its `REVISION` build argument |
| `SOFA_VERSION` | `unknown`; the image sets it | The product version the server was built from, shown on the admin portal's About page. The image sets it from its `VERSION` build argument |
| `SOFA_UPDATE_IMAGE` | `ghcr.io/jamesarich/sofa-king:main` | The published image, as `host/name:tag`, whose index the [update check](admin.md#update) reads to say whether an update is available. Set it empty to turn the check off |
| `TZ` | UTC | Time zone of the process, for example `America/Chicago`. The server stores and serves its times in UTC either way |

## Source settings

The source file is a JSON object with `providers` and `guides`. A bare JSON array is read as `providers`. Inside any string, `${NAME}` is replaced with the environment variable `NAME`; an unset variable fails startup. Keep tokenized paths in the environment, not in the file. The file takes no comments, and unknown keys fail startup.

### Providers

Each provider object configures one M3U playlist, or with `"type": "native"` one built-in service client ([native providers](providers.md)):

| Field | Default | Description |
| --- | --- | --- |
| `id` | Required | Unique provider ID: 1-64 letters, digits, underscores or hyphens |
| `type` | `m3u` | `m3u` for a playlist, `native` for a service client |
| `playlistUrl` | Required for `m3u` | HTTP(S) M3U playlist URL; absent for `native` |
| `service` | Required for `native` | The service name, see [native providers](providers.md#configuration) |
| `options` | `{}` | Service options for `native`, for example `{"zip": "ZIP_CODE"}` for `pbs` |
| `guideUrl` | Unset | HTTP(S) XMLTV URL; an implicit guide with this provider's id. A native service with a guide of its own, or a published XMLTV, gets the implicit guide without it |
| `headers` | `{}` | Request headers for playlists, guides and streams (`m3u` only) |
| `refreshSeconds` | `900` (`3600` for `native`) | Import interval; minimum 60 seconds |
| `probe` | `true` | Enable automatic source qualification. A lineup policy ignores it and probes every source its stations use ([probing](lineup-policy.md#probing)) |
| `defaultProtocol` | Unset | `hls` or `mpegts` for entries without a `stream-type`; otherwise `.m3u8` implies HLS and other paths imply MPEG-TS. Applies until a probe or media fetch detects the protocol in the stream body |
| `allowEmpty` | `false` | Allow an empty playlist to withdraw all of this provider's sources |
| `renewableQuery` | Unset | Query parameter names (case-insensitive) that this provider rotates; replaces the [default list](#source-identity) for its stream URLs |

Keep provider IDs stable: they participate in source identity, and lineup entries refer to sources by ID. Keep credentials in the environment and reference them as `${NAME}`, or keep the source file in the data directory, which Git ignores.

Failed imports keep the last complete snapshot. Removed sources become unavailable; lineup entries keep referring to them and skip them for playback until they return. With `allowEmpty: false`, an empty playlist also keeps the previous snapshot.

A malformed entry (no name; the same source key twice without a `stream-id`; a non-HTTP URL; an unsupported `stream-type`; a URL without `#EXTINF`) is skipped and counted, and the rest of the playlist imports. `/api/v1/providers` reports `skippedEntries`, `skipReasons` and `notes` (entries imported with a derived identity), and the server logs the counts without URLs. A playlist that is not extended M3U, exceeds an import limit, or skips every entry without `allowEmpty` fails the import and keeps the previous snapshot.

An M3U provider, in the shape `deploy/sources.example.json` shows:

```json
{ "id": "example-m3u", "playlistUrl": "https://PLAYLIST_HOST/path/playlist.m3u", "guideUrl": "https://GUIDE_HOST/path/guide.xml.gz" }
```

### Source identity

Every stream URL has a source key: the lowercase scheme and authority, the percent-decoded path, and the remaining query parameters sorted by name, after the renewable parameters are dropped. The default renewable names are `token`, `access_token`, `auth`, `sig`, `signature`, `expires`, `exp`, `key`, `hash`, `hmac` and `nonce`, compared case-insensitively. A provider's `renewableQuery` replaces that list for its URLs. Every other parameter carries identity, so `?id=1` and `?id=2` are two sources, and `?pl=dlhd&token=A` and `?pl=dlhd&token=B` are one. A restream proxy whose path carries a percent-encoded upstream URL keeps the whole upstream URL in the key, so two upstreams that differ anywhere inside it are two sources.

A channel's identity is its `channel-uuid`, else its `channel-id`, else its `tvg-id`, else the source key of its URL. An entry without a published identity is its own channel: the channel ID and source ID derive from the provider ID and the source key. The name, logo and group are display values that may change between refreshes, `legacyGuideId` is empty, and without `tvg-chno` the number is the first eight characters of the channel ID, so renumber after adopting. A published identity repeated with another name or number keeps the first entry's name and number and adds the URL as an alternative source. An explicit `stream-id` replaces the source key in the source ID. The same source key twice under one channel is `duplicate-source`, and the second URL is dropped.

Source IDs survive token rotation, refreshes and reordering.

A native provider's sources have `svc://SERVICE/CHANNEL_ID` URLs. These are stable identities that the server resolves to a playable URL when a session, a probe or the HDHomeRun remux needs one ([how a native source plays](providers.md#how-a-native-source-plays)). A native candidate's `tvg-id` is the service's channel ID, which the service's implicit guide lists, so adding a candidate maps the guide.

### Guides

Each guide object configures one XMLTV source, independent of any provider:

| Field | Default | Description |
| --- | --- | --- |
| `id` | Required | Unique guide ID: 1-64 letters, digits, underscores or hyphens; distinct from provider IDs that have a `guideUrl` |
| `url` | Required | HTTP(S) XMLTV URL, plain or gzip (detected by content, not by extension) |
| `headers` | `{}` | Request headers for the guide |
| `refreshSeconds` | `21600` | Import interval; minimum 300 seconds |

The server keeps every guide's channel index for guide mapping, and programs only for the channels the lineup maps onto that guide. Adding or changing a mapping triggers a refresh of that guide, so a newly mapped channel has programs within one fetch. Each guide fails on its own: `/api/v1/admin/guides` reports `IMPORT_FAILED` for the failing one, and the others keep refreshing. [Guide imports](architecture.md#guide-imports) describes the parser, its redirects and timeouts, and the XML it accepts.

### Import limits

| Input | Limit |
| --- | --- |
| Source configuration | 1 MiB |
| Playlist | 16 MiB |
| XMLTV, decompressed | 1 GiB |
| Channels per provider | 10,000 |
| Sources per provider | 20,000 |
| Channels per guide | 100,000 |
| Retained programs per guide | 1,000,000 |
| Program title and sub-title | 4,096 characters |
| Program description | 16,384 characters |
| Categories, episode numbers per program | 8 each |

Of each program the server keeps the title, sub-title, description, every `<episode-num>` with its `system` (64 characters at most), the `<icon>` (resolved like a channel icon), the `<category>` values (128 characters each), the first `<rating>` value and system, `<new/>`, `<previously-shown>` and its `start`, the `<date>` (as `YYYY`, `YYYYMM` or `YYYYMMDD`) and the title's `lang` (else the description's, else `<language>`). Only the program's own children count, so an icon inside credits or a star rating is not the program's. Titles, descriptions and categories over their length are cut; an episode number, rating, date or language that is too long or malformed, and every category or episode number past the eighth, is dropped without dropping the program.

The XMLTV limit counts decompressed bytes as they stream past the parser, so it caps the download without buffering it. A guide that crosses it fails with `IMPORT_FAILED` and `Guide exceeds size limit` in the log.

## Lineup

The app sees the lineup and nothing else: `/api/v1/channels`, `/api/v1/guide/query` and `/api/v1/playback-sessions` work on lineup entry IDs. The channels providers list are candidates. With a [lineup policy](lineup-policy.md) the policy builds the lineup from them; without one, entries are added through the admin API, as [build a lineup without a policy](starter-lineup.md#build-a-lineup-without-a-policy) shows.

A lineup entry has a stable UUID (the app's channel ID), a number, a name, an optional group and logo override, and an enabled flag. It also has an ordered list of sources from any providers, the failover order, and an optional guide mapping (guide ID plus XMLTV channel ID). Entries live in SQLite and survive imports and restarts. A disabled entry is kept but not served.

Playback takes an entry's sources in the [selection order](source-health.md#source-selection). `tags` carries the group, and `legacyGuideId` carries the mapped XMLTV channel ID.

With a lineup policy, `PATCH` and `DELETE` on `/api/v1/admin/lineup/{id}` write the station's override in the policy instead, and the routes that add, reorder or adopt answer `410` ([lineup policy API](lineup-policy.md#api)).

### Logos

Clients, players and Plex see every logo at `http://SERVER_HOST:9193/logos/KEY` on this server, not on the logo's source host. A channel's logo is the entry's own, else the first of its sources' `tvg-logo` values, else the mapped guide channel's `<icon>`. The server keeps each logo under `SOFA_DATA_DIR/logos/` and refreshes it weekly; a refresh that fails keeps the stored file, so retiring a logo host leaves the logos in place. [API logos](api.md#logos) describes what clients get, and [architecture](architecture.md#logos) how logos are fetched and stored.

## Third-party players

The lineup is also served as an M3U playlist with an XMLTV guide, for any player that takes an M3U playlist URL ([API detail](api.md#player-outputs)). Set `SOFA_OUTPUT_TOKEN` (32 or more characters, from `openssl rand -hex 16`) and restart. The token goes in the URLs because players send no headers. Replace `SERVER_HOST:9193` with the address the player reaches the server by (port 8080 outside Docker):

```text
http://SERVER_HOST:9193/output/playlist.m3u?token=OUTPUT_TOKEN
http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN
```

The playlist names the guide URL (`url-tvg`), uses the lineup channel ID as `tvg-id` on both sides, and lists each channel's stream as `/output/stream/CHANNEL_ID.m3u8` (HLS source) or `.ts` (MPEG-TS source), with the token. Opening a stream URL opens a watch session. The session ends when the player disconnects (TS) or 60 seconds after its last request (HLS). Players count against `SOFA_MAX_SESSIONS`. An idle player session gives way to the next channel change, and a busy server answers `429`.

To set up a player:

1. Add the playlist URL as a playlist or IPTV source. Most players call this "add playlist" or "enter URL".
2. If the player reads `url-tvg` from the playlist header, it offers the guide URL as the EPG source, and you can accept it. Otherwise add the guide URL as an EPG or XMLTV source.
3. Set the playlist refresh to a few hours, so a changed stream suffix or a renumbered channel reaches the player. Channels match the guide by `tvg-id`, so no manual mapping is needed.
4. To change the token, update `SOFA_OUTPUT_TOKEN`, restart the server, and update the playlist and guide URLs in each player.

Players that accept a single URL can open a stream URL directly, without the playlist.

## Plex Live TV

Plex Live TV & DVR takes an HDHomeRun tuner and an XMLTV guide. The server emulates the tuner at the root of its port ([API detail](api.md#hdhomerun-emulation)). The guide is the [player output](#third-party-players) XMLTV, so Plex needs `SOFA_OUTPUT_TOKEN` for the guide URL and no token for the tuner.

The tuner routes carry no token: anyone who can reach the port can list the lineup and watch through `/auto/vNUMBER`. Keep the port on the LAN and set `SOFA_HDHR_ALLOW` to the Plex server's address. Behind Docker on Linux, a published port keeps the client address with the default iptables setup. Where Docker's userland proxy handles the port, the server sees the Docker gateway address instead, and the allow-list must name that address. Set `SOFA_HDHR_ENABLED=false` when Plex is not used.

The steps use the Docker port, 9193. Outside Docker the port is `SOFA_PORT`, 8080 by default.

1. In `deploy/.env`, set `SOFA_HDHR_ENABLED=true`, `SOFA_HDHR_ALLOW=PLEX_SERVER_IP` and `SOFA_HDHR_TUNERS` to the number of simultaneous Plex streams and recordings, at most `SOFA_MAX_SESSIONS`. Set `SOFA_OUTPUT_TOKEN` too, for the guide URL. Run `docker compose up -d`, then check from the Plex host with `curl --fail http://SERVER_HOST:9193/discover.json`.
2. In Plex Web, open Settings, then Live TV & DVR, then Set Up Plex DVR. The server does not answer SSDP discovery, so choose "Don't see your HDHomeRun device? Enter its network address manually" and enter `SERVER_HOST:9193`. Plex reads `/discover.json` and `/lineup.json` and lists the device by `SOFA_HDHR_NAME` with its tuner count.
3. Plex shows the channels from `/lineup.json`. At the guide step, choose "Have an XMLTV guide on your server?" and enter `http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN`. Plex fetches the guide on its own schedule. Keep `SOFA_XMLTV_DAYS` at 3 or more so a daily refresh never runs out of programs.
4. Map the channels. The XMLTV lists each channel's name and number as display names, and the lineup carries the same values, so Plex pairs most of them. Choose the rest from the dropdowns. Plex saves a device's channel map in one request, and a very large map on one device fails with "There was a problem saving channel mappings". Split a large lineup across devices, as described below.
5. Play a channel. Plex opens `/auto/vNUMBER`. An MPEG-TS source streams through the fan-out. An HLS source is remuxed to MPEG-TS by FFmpeg with stream copy, so CEA-608 captions inside the video reach Plex's caption setting. When every tuner or session slot is taken, Plex reports "all tuners are in use".

After a lineup change, a new channel or a renumbering, Plex must re-read `/lineup.json` and save the channel map again before the channel plays. Renumbering changes the stream URL. With [Plex sync](#keep-plex-in-step) the server does this; without it, run "Scan channels" for the DVR in Plex.

### Keep Plex in step

With `SOFA_PLEX_URL` and `SOFA_PLEX_TOKEN` set, the server keeps the channel maps of its devices in Plex in step with the lineup. A sync runs 2 minutes after a lineup policy apply changes the lineup, every `SOFA_PLEX_SYNC_MINUTES`, 2 minutes after start, and on `POST /api/v1/admin/plex/sync`.

A sync reads Plex's DVRs and picks out the devices whose DeviceID is one of this server's. For each, it takes the channel map Plex proposes against the DVR's guide, saves it with every proposed channel enabled, then reloads that DVR's guide once. Other tuners in the same DVR are left alone. A channel Plex proposes no guide channel for stays out of the map.

- A device this server serves that no DVR holds is reported as a finding, `plex: device DEVICEID (device N) is not added to Plex`, where N counts from 0 on `SOFA_PORT`. With `SOFA_PLEX_TUNER_BASE` set, the sync adds it instead, at `SOFA_PLEX_TUNER_BASE:PORT` with the port from `SOFA_HDHR_PUBLIC_PORTS`, to the first DVR that holds one of this server's devices, then maps it. A device that answers with another DeviceID is not added.
- Plex refuses a channel map whose request runs past about 30 KB, roughly 250 channels. A device over that is skipped with the finding `plex: device DEVICEID has N channels, too many for Plex to save; lower SOFA_HDHR_DEVICE_SIZE or add ports`.
- A failed request is reported in `lastError` and as the finding `plex: sync failed: ...`; the other devices are still mapped.
- `GET /api/v1/admin/plex` returns the last sync ([API detail](api.md#admin-api)). The database keeps it, so after a restart the API and the portal's Plex page show the last sync with its time until the next one replaces it. With a lineup policy, the findings also appear in `GET /api/v1/admin/policy/findings`.

Plex must already reach the server: the URL is used as configured, without the upstream address rules, and redirects are not followed. `deploy/compose.yaml` maps `host.docker.internal` to the Docker host, so a Plex server on the same host is `http://host.docker.internal:32400`.

The token is the Plex server's `X-Plex-Token`, and anyone holding it administers the Plex server. Take it from the `PlexOnlineToken` attribute in the server's `Preferences.xml`, or in Plex Web open any item's "Get Info", then "View XML", and copy the `X-Plex-Token` value from the address. Keep it in `deploy/.env` with the other secrets.

### Several devices for a large lineup

`SOFA_HDHR_EXTRA_PORTS` lists further ports the server listens on, up to seven. Each port is one more HDHomeRun device, named `SOFA_HDHR_NAME 2`, `SOFA_HDHR_NAME 3` and so on, and each carries its share of the lineup.

- The lineup is split by a stable hash of each entry's ID, so a channel stays on its device when other channels change. Changing the number of ports reassigns channels across the devices.
- With a [lineup policy](lineup-policy.md#hdhomerun-devices), devices hold consecutive ranges instead: the first `SOFA_HDHR_DEVICE_SIZE` enabled channels in number order on the first device, the next on the second. Channels beyond the last device's range stay on it, and the server logs that more ports are needed.
- Each device has its own `SOFA_HDHR_TUNERS` tuners. All devices share `SOFA_MAX_SESSIONS`, so the total number of concurrent streams is still capped by it.
- Publish every extra port in `deploy/compose.yaml`, then restart after changing the list.
- Add each device to the same DVR, with `SERVER_HOST:PORT` for its port and the same XMLTV guide URL.

For two extra devices:

```text
SOFA_HDHR_EXTRA_PORTS=8081,8082
```

The extra ports must differ from `SOFA_PORT`. They are opened only when `SOFA_HDHR_ENABLED` is `true`.

## Provider headers

Credentials are sent only to the configured request origin. Cross-origin CDN requests receive only `User-Agent`, `Accept`, `Accept-Language`, `Origin` and `Referer`. Authorization, cookies and custom headers are removed. Redirects are limited to five. HTTPS redirects cannot downgrade to HTTP, and sources configured with HTTPS also reject HTTP media resources.

Providers that need secret headers on a different CDN origin are unsupported. A provider with any other header is never probed ([probing](lineup-policy.md#probing)).

## Upstream addresses

Playlist, guide, probe and media requests go only to `http`/`https` URLs without user info. Loopback (`127.0.0.0/8`, `::1`, `localhost`), link-local (`169.254.0.0/16`, `fe80::/10`), unspecified (`0.0.0.0`, `::`) and multicast literals are refused, including as redirect targets. Private LAN addresses (RFC 1918) are allowed. Hostnames are not resolved before the request. A refused media address answers `502 UPSTREAM_REJECTED`, a refused playlist or guide address fails that import, and a refused probe target counts as evidence against the source. `SOFA_ALLOW_LOOPBACK_UPSTREAMS=true` permits loopback for local fixtures.

FFmpeg reads sources for probes, recordings and the Plex remux through the server's own relay on `127.0.0.1`, which fetches every redirect, variant, segment and key under these rules, so a refused hop fails the read. It is started with `-protocol_whitelist http,tcp,crypto`, so a playlist that names `https:`, `file:`, `concat:` or another protocol fails instead of reaching another host or reading files on the server.

## Troubleshooting

[Troubleshooting](troubleshooting.md) covers empty lineups, imports, sources, the guide, players, logos and Plex.
