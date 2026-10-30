# Server configuration and live TV

This page is the configuration reference for the server: every environment variable, the source file that lists providers and guides, how sources and channels are identified, the lineup, the player outputs and Plex. To run the server on the LAN without Docker, see [development](development.md#run-without-docker); the tokens each route takes are in [security](security.md#credentials-and-the-routes-they-open).

## Server settings

Set these environment variables before starting the server. Relative paths resolve from the server's working directory. Configuration changes require a restart, except the nine runtime settings (`SOFA_MAX_SESSIONS`, `SOFA_RECORDINGS_FREE_GIB`, `SOFA_RECORDINGS_KEEP_DAYS`, `SOFA_LEARNED_BREAKS`, `SOFA_AD_LISTENERS`, `SOFA_XMLTV_DAYS`, `SOFA_HDHR_TUNERS`, `SOFA_PLEX_SYNC_MINUTES` and `SOFA_LOG_LEVEL`): a value saved on the portal's [Settings page](admin.md#settings) applies at once and takes precedence over the environment. A token the environment leaves unset is generated on a non-loopback bind, as [security](security.md#credentials-and-the-routes-they-open) describes. The [Docker image](deploy.md) sets `SOFA_HOST=0.0.0.0`, `SOFA_PORT=8080` and `SOFA_DATA_DIR=/config`. `deploy/compose.yaml` runs it on the [host's network](deploy.md#host-networking) and sets `SOFA_PORT=9193`.

| Variable | Default | Description |
| --- | --- | --- |
| `SOFA_HOST` | `127.0.0.1` | Listen address |
| `SOFA_PORT` | `8080`; `9193` in `deploy/compose.yaml` | Listen port, 1-65535 |
| `SOFA_MDNS` | `true` off loopback, `false` on loopback | Advertise the server on the LAN over mDNS, so the Sofa King app finds it without an address, as [discovery](api.md#discovery) describes |
| `SOFA_DATA_DIR` | `data` | Directory for the database, the logo cache and `secrets.json`, and where `sources.json` and `policy.json` are looked for |
| `SOFA_RECORDINGS_DIR` | `SOFA_DATA_DIR/recordings`; `/recordings` in `deploy/compose.yaml` | Folder the [recordings](recordings.md) are kept in, laid out as [the recordings folder](deploy.md#the-recordings-folder) describes. |
| `SOFA_SOURCES` | `SOFA_DATA_DIR/sources.json` when it exists | Path to the source configuration JSON file; with neither, the server starts with no providers or guides |
| `SOFA_POLICY` | `SOFA_DATA_DIR/policy.json` when it exists | Path to a [lineup policy](lineup-policy.md) JSON file in a writable directory, loaded and validated at start (any error stops the server). With a policy, the lineup is built from it |
| `SOFA_API_TOKEN` | Generated on a non-loopback bind | API bearer token, at least 32 characters. On loopback, unset leaves the API open |
| `SOFA_ADMIN_TOKEN` | Generated on a non-loopback bind | Bearer token for `/api/v1/admin/*` and the admin portal, at least 32 characters. On loopback, unset means the API token administers the lineup too |
| `SOFA_API_TOKEN_FILE`, `SOFA_ADMIN_TOKEN_FILE`, `SOFA_OUTPUT_TOKEN_FILE`, `SOFA_PLEX_TOKEN_FILE`, `SOFA_TMDB_TOKEN_FILE`, `SOFA_FANART_KEY_FILE`, `SOFA_LISTENBRAINZ_TOKEN_FILE` | Unset | A file holding the token, for Docker secrets. The value is read at start without its trailing line break and validated like the variable. Setting a variable and its `_FILE` variant, or naming an unreadable file, stops the start |
| `SOFA_MAX_SESSIONS` | `4` | Maximum active playback sessions, 1-16 |
| `SOFA_RECORDINGS_FREE_GIB` | `10` | Free space, in GiB, the DVR keeps on the recordings folder's disk by deleting the oldest recordings, 0-100000; see [cleanup](recordings.md#cleanup) |
| `SOFA_RECORDINGS_KEEP_DAYS` | `0` | Days the DVR keeps a recording, 0-3650; `0` keeps it until space runs short |
| `SOFA_LEARNED_BREAKS` | `shadow` | What the app's live sessions do with ad breaks the server learns from a stream that marks none, such as a long silent slate: `off`, `shadow` (log each as `ADDETECT`, mark nothing) or `on` (mark it for the break screen) |
| `SOFA_AD_LISTENERS` | `6` | Channels the server plays in the background at once, 0-24 (at most 2 while anyone watches), to learn the ads of channels that mark none from the ones that do; see [learned ad breaks](architecture.md#learned-ad-breaks). `0` or `SOFA_LEARNED_BREAKS=off` turns them off |
| `SOFA_COMSKIP` | `comskip` | Comskip executable that finds [commercial breaks](recordings.md#commercial-breaks) in finished recordings; `none` turns the scan off. The image installs it |
| `SOFA_MUSICBRAINZ` | `true` | `false` stops the [MusicBrainz lookups](libraries.md#details-from-musicbrainz) for Music libraries' albums and artists after each scan, and the [ListenBrainz](libraries.md#listening-statistics-from-listenbrainz) and [LRCLIB lyrics](libraries.md#lyrics) ones with them |
| `SOFA_FANART_KEY` | Unset | A [fanart.tv](https://fanart.tv/get-an-api-key/) API key. With it, Music libraries take [artists' pictures and backdrops](libraries.md#artwork-from-fanarttv) and more album covers from fanart.tv. Unset, the server uses the key the portal's Library page saved in `secrets.json`, if any |
| `SOFA_LISTENBRAINZ_TOKEN` | Unset | A [ListenBrainz](https://listenbrainz.org/settings/) user token. With it, Music libraries ask ListenBrainz for each artist's [most played songs](libraries.md#listening-statistics-from-listenbrainz), which it answers only with a token. Unset, the server uses the token the portal's Library page saved in `secrets.json`, if any |
| `SOFA_QUALITY_FLOORS` | `false` | `true` also moves a remuxed stream (Plex, HDHomeRun and `format=ts` players) to the channel's next source when its picture stays black or its rate stays under 64 kbit/s for 30 seconds, not only when it stalls; see [stall and quality failover](source-health.md#stall-and-quality-failover). It decodes the video's keyframes, so it costs some CPU per remuxed stream |
| `SOFA_FFMPEG` | `ffmpeg` | FFmpeg executable used by source probes and the HDHomeRun remux; FFprobe is looked for beside it, or on the path when this is a bare name |
| `SOFA_MEDIA_DIR` | `SOFA_DATA_DIR/media`; `/media` in `deploy/compose.yaml` | The folder [media libraries](libraries.md) are added from. The server only reads it; mount it read only |
| `SOFA_LIBRARY_PATH_MAP` | Unset | How Sonarr, Radarr and other tools that call the [hooks](api.md#hooks) name the media folders, when they mount them elsewhere: `from=to` pairs separated by commas, such as `/data/media/tv=/media/tv`. A path under `from` is read as the same path under `to`, which must be absolute |
| `SOFA_TMDB_TOKEN` | Unset | A [TMDB](https://www.themoviedb.org/settings/api) API read access token or v3 API key, at least 32 characters. With it, library scans [look up details and artwork](libraries.md#details-from-tmdb) for movies and shows. Unset, the server uses the token the portal's Library page saved in `secrets.json`, if any |
| `SOFA_MAX_TRANSCODES` | `2` | Library files FFmpeg converts or remuxes at once for [library playback](api.md#play-library-files), with each playing [virtual channel](virtual-channels.md) and [multiview](api.md#multiview) counted too, 1-8. Direct play is not counted |
| `SOFA_HWACCEL` | `none`; `auto` in `deploy/compose.yaml` | The GPU encoder library files are converted with: `auto` (the first of NVENC, VA-API and Quick Sync that encodes a test frame at start, else software), `vaapi` (Intel or AMD), `qsv` (Intel Quick Sync) or `nvenc` (NVIDIA); `none` converts in software with libx264. See [hardware transcoding](libraries.md#hardware-transcoding) |
| `SOFA_HWACCEL_DEVICE` | `/dev/dri/renderD128` | The render node `vaapi` and `qsv` open |
| `SOFA_MULTIVIEW` | `auto` | Whether the server draws [multiview](api.md#multiview): `auto` only when the `SOFA_HWACCEL` encoder works, at 1080p; `on` in software too, at 720p; `off` never |
| `SOFA_TMDB_LANGUAGE` | `en-US` | The language TMDB answers in, such as `de` or `pt-BR`; its region picks the content rating |
| `SOFA_PROBE_PARALLELISM` | `6` | Concurrent source probes, 1-32 |
| `SOFA_RESTREAM_ORIGINS` | Unset | Comma-separated `host[:port]` list of LAN restream proxies whose 502 counts as source evidence, for example `RESTREAM_HOST:3000` |
| `SOFA_OBSERVATION_RETENTION_DAYS` | `7` | Days of health observation history to keep, 1-3650 |
| `SOFA_HISTORY_DAYS` | `90` | Days of [playback history](api.md#playback-history) to keep, 1-3650: one row per live, library or recording session once it ends |
| `SOFA_ALLOW_LOOPBACK_UPSTREAMS` | `false` | Allow playlist, guide and stream URLs on loopback (`127.0.0.1`, `::1`, `localhost`); for local fixtures only |
| `SOFA_OUTPUT_TOKEN` | Generated on a non-loopback bind | Token for the [player outputs](#third-party-players) under `/output/*`, at least 32 letters, digits, hyphens or underscores. It goes in the URL, so it is separate from the API token. On loopback, unset leaves only the API token (as a bearer) to open them |
| `SOFA_XMLTV_DAYS` | `3` | Days ahead the XMLTV output carries, 1-14 |
| `SOFA_HDHR_ENABLED` | `true` | Serve the [HDHomeRun emulation](#plex-live-tv) (`/discover.json`, `/lineup.json`, `/auto/v*`) at the root. It admits no address off loopback until one is allowed |
| `SOFA_HDHR_TUNERS` | `2` | Tuners each device exposes, which is the number of HDHomeRun streams open at once on that device, 1 to `SOFA_MAX_SESSIONS` |
| `SOFA_HDHR_DEVICE_ID` | Derived from the server identity | Eight hex digits Plex identifies the tuner by; keep it stable or Plex sees a new device |
| `SOFA_HDHR_NAME` | `Sofa King` | Friendly name Plex shows, 1-64 characters without control characters |
| `SOFA_HDHR_ALLOW` | Unset | Client IPs or CIDR blocks admitted to the HDHomeRun routes, comma separated (for example `PLEX_SERVER_IP` or `LAN_CIDR`). The portal's Plex page allows more at run time. With neither, a loopback bind admits this machine but not a reverse proxy, and any other bind admits none |
| `SOFA_HDHR_EXTRA_PORTS` | Unset | Further ports the server listens on, comma separated, up to 15. Each is one more HDHomeRun device (`SOFA_HDHR_NAME 2`, and so on) carrying its share of the lineup. See [several devices](#several-devices-for-a-large-lineup) |
| `SOFA_HDHR_DEVICE_SIZE` | `250` | With a lineup policy, channels per HDHomeRun device; devices hold consecutive ranges of the lineup. 1 to 5000 |
| `SOFA_HDHR_PUBLIC_PORTS` | `SOFA_PORT`, then `SOFA_HDHR_EXTRA_PORTS` | The host ports Plex reaches each device on, comma separated, one per device in device order. Set it when Docker publishes the ports under other numbers |
| `SOFA_PLEX_URL` | Unset | The Plex server to [keep in step](#keep-plex-in-step) with the lineup, as `http(s)://HOST[:PORT]` with no path, query or credentials (for example `http://host.docker.internal:32400`). Set it with `SOFA_PLEX_TOKEN`; requires `SOFA_HDHR_ENABLED` |
| `SOFA_PLEX_TOKEN` | Unset | The Plex server's `X-Plex-Token`, at least 8 characters without spaces, control characters or DEL. A secret: it is sent only as a request header and never logged |
| `SOFA_PLEX_SYNC_MINUTES` | `60` | Minutes between Plex syncs when the lineup does not change, 5 to 1440 |
| `SOFA_PLEX_TUNER_BASE` | Unset | Scheme and host Plex reaches this server's tuners on, as `http(s)://HOST` with no port, path, query or credentials, for example `http://192.168.1.20`. When set, a sync adds devices Plex lacks |
| `SOFA_LOG_LEVEL` | `info` | Log level on stderr: `trace`, `debug`, `info`, `warn`, `error` or `off`; the Events page keeps INFO and above whatever it is |
| `SOFA_LOG_FORMAT` | `text` | `text` for one line per event, or `json` for one JSON object per line with the level, logger, message and request id. Logs go to stderr and the log file. See [logs](operations.md#logs) |
| `SOFA_LOG_DAYS` | `14` | Days of log files kept in `logs/` in `SOFA_DATA_DIR`, 0-365, so the log outlives an updated container; `0` writes no file. See [logs](operations.md#logs) |
| `SOFA_CORS_ORIGINS` | Unset | Comma-separated browser origins, such as `https://tv.example.com,http://192.168.1.20:8080`, that may call the API from a page on another origin, for example the Sofa King web app served elsewhere. Each is `scheme://host[:port]` with no path; `*` is not accepted. Unset sends no CORS headers, so only pages on the server's own origin can use the API from a browser. Tokens still authorize every call; cookies never cross origins |
| `SOFA_WEB_DIR` | Unset; `/opt/sofa/web` in the image | The folder holding the app's web build, which the server serves at `/app/` with no token, and `/` redirects to. Unset, or a folder without `index.html`, serves no web app. See [serve the web app](deploy.md#serve-the-web-app) |
| `SOFA_REVISION` | `unknown`; the image sets it | The commit the server was built from, reported by `GET /api/v1/admin/status` and the admin portal footer. The image sets it from its `REVISION` build argument |
| `SOFA_VERSION` | `unknown`; the image sets it | The product version the server was built from, shown on the admin portal's About page. The image sets it from its `VERSION` build argument |
| `SOFA_UPDATE_IMAGE` | `ghcr.io/jamesarich/sofa-king:main` | The published image, as `host/name:tag`, whose index the [update check](admin.md#update) reads to say whether an update is available. Set it empty to turn the check off |
| `TZ` | UTC | Time zone of the process, for example `America/Chicago`. The server stores and serves its times in UTC either way |

## Source settings

The source file is a JSON object with `providers` and `guides`. A bare JSON array is read as `providers`. Inside any string, `${NAME}` is replaced with the environment variable `NAME`; an unset variable fails startup. Keep tokenized paths in the environment, not in the file. The file takes no comments, and unknown keys fail startup. Requests to the hosts it names follow the [outbound address and header rules](security.md#outbound-requests).

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
| `offsetMinutes` | `0` | Minutes added to every program's start and end at import, -1440 to 1440, for a guide that writes the wrong time zone offset |

The server keeps every guide's channel index for guide mapping, and programs only for the channels the lineup maps onto that guide. Adding or changing a mapping triggers a refresh of that guide, so a newly mapped channel has programs within one fetch. A station whose sources no guide lists by `tvg-id` gets guide channels proposed by name on the portal's Guide matches tab ([guide matches](lineup-policy.md#guide-matches)). Each guide fails on its own: `/api/v1/admin/guides` reports `IMPORT_FAILED` for the failing one, and the others keep refreshing. [Guide imports](architecture.md#guide-imports) describes the parser, its redirects and timeouts, and the XML it accepts.

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

The lineup is also served as an M3U playlist with an XMLTV guide, for any player that takes an M3U playlist URL ([API detail](api.md#player-outputs)). The URLs carry the output token because players send no headers: the server generates one at the first start, and the admin portal's Plex page shows both URLs with it once you choose Reveal the output token. `SOFA_OUTPUT_TOKEN` sets your own instead. Replace `SERVER_HOST:9193` with the address the player reaches the server by (port 8080 outside Docker):

```text
http://SERVER_HOST:9193/output/playlist.m3u?token=OUTPUT_TOKEN
http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN
```

Opening a stream URL opens a watch session that counts against `SOFA_MAX_SESSIONS`; the playlist format, the session's life and the `429` are in [player outputs](api.md#player-outputs).

To set up a player:

1. Add the playlist URL as a playlist or IPTV source. Most players call this "add playlist" or "enter URL".
2. If the player reads `url-tvg` from the playlist header, it offers the guide URL as the EPG source, and you can accept it. Otherwise add the guide URL as an EPG or XMLTV source.
3. Set the playlist refresh to a few hours, so a changed stream suffix or a renumbered channel reaches the player. Channels match the guide by `tvg-id`, so no manual mapping is needed.
4. To change the token, [rotate it](operations.md#rotating-tokens), then update the playlist and guide URLs in each player.

Players that accept a single URL can open a stream URL directly, without the playlist.

### Named outputs

A named output is a playlist and guide of part of the lineup, for a player that should see only some channels, such as a kids' room TV. Add one under Named outputs on the admin portal's Plex page: give it a name, the groups it holds and any channel numbers or IDs. A channel is in the output when its group is one of those named, ignoring case, or its number or ID is listed. The output has its own URLs, with the same token:

```text
http://SERVER_HOST:9193/output/NAME/playlist.m3u?token=OUTPUT_TOKEN
http://SERVER_HOST:9193/output/NAME/guide.xml?token=OUTPUT_TOKEN
```

The playlist names its own guide, and its streams stay at `/output/stream/CHANNEL_ID`. Saving a name that exists replaces that output, so a player keeps its URLs while you change what it holds. A named output does not add HDHomeRun devices: Plex adds a tuner by address and port only, so Plex keeps the whole lineup ([API detail](api.md#named-outputs)).

### Channels DVR

Channels DVR takes the playlist as a custom channels source, with one stream format for the whole source, so use the playlist with `format=ts`, which serves every channel as MPEG-TS (an HLS source is remuxed on the server, as for Plex):

1. In Channels DVR's web settings, add a source and choose Custom Channels.
2. Name it, set Stream Format to MPEG-TS, and add it by URL: `http://SERVER_HOST:9193/output/playlist.m3u?token=OUTPUT_TOKEN&format=ts`.
3. For the guide, set the source's XMLTV guide data to `http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN`. A channel with a Gracenote station id takes its guide from Gracenote instead.

Each Channels stream is a player session and counts against `SOFA_MAX_SESSIONS`.

For a channel that broadcasts over the air or on cable, set its Gracenote (TMS) station id on its [Channel page](admin.md#pages) or as `gracenoteId` in the [lineup policy](lineup-policy.md#station-overrides); `scripts/generate-policy.py --station-ids` takes a CSV of them. The playlist then carries `tvc-guide-stationid`, and Channels shows Gracenote's programs, artwork and series ids for it. Any other channel with a logo carries `tvc-guide-art`, which Channels shows as the art of programs the guide has no image for.

### Jellyfin

Jellyfin takes the lineup either as an HDHomeRun tuner or as an M3U tuner, and the guide as XMLTV:

1. In the Jellyfin dashboard, open Live TV and add a tuner device. For HDHomeRun, enter `http://SERVER_HOST:9193` and allow the Jellyfin server's address in `SOFA_HDHR_ALLOW` (or on the portal's Plex page), as for [Plex](#plex-live-tv); it plays every channel as MPEG-TS. For M3U Tuner, enter the playlist URL with the token.
2. Add a TV guide data provider of type XMLTV with the guide URL with the token.
3. Map channels by `tvg-id` if Jellyfin asks; the playlist and the guide use the same channel IDs.
4. Run the Refresh Guide scheduled task after a lineup change, or wait for its daily run.

## Plex Live TV

Plex Live TV & DVR takes an HDHomeRun tuner and an XMLTV guide. The server emulates the tuner at the root of its port ([API detail](api.md#hdhomerun-emulation)). The guide is the [player output](#third-party-players) XMLTV, so Plex needs the output token for the guide URL and no token for the tuner.

The tuner routes carry no token, so the server admits them by the client's address: only the addresses allowed on the portal's Plex page, under Tuner access, or listed in `SOFA_HDHR_ALLOW`. Until one is allowed, the tuners refuse everyone, and the Plex page and the home page list the addresses refused since the start, each with Allow. That is the easy way to find the address the server sees. With `deploy/compose.yaml`, on the host's network, it is Plex's own address. On a Docker bridge network, where Docker's userland proxy handles a published port, or where Plex runs in a container on a bridge network, the server sees a Docker address instead. Keep the port on the LAN. Set `SOFA_HDHR_ENABLED=false` when Plex is not used.

The steps use the Docker port, 9193. Outside Docker the port is `SOFA_PORT`, 8080 by default.

1. On the portal's Plex page, under Tuner access, allow the Plex server's address. Or skip this step: once Plex has tried the tuner in step 2, the page lists the address it was refused from, and Allow lets it in at once. Check from the Plex host with `curl --fail http://SERVER_HOST:9193/discover.json`. Each device takes two streams at once; on the Settings page, set the streams each device may open (or `SOFA_HDHR_TUNERS`) to the number of simultaneous Plex streams and recordings, at most `SOFA_MAX_SESSIONS`.
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

`SOFA_HDHR_EXTRA_PORTS` lists further ports the server listens on, up to 15. Each port is one more HDHomeRun device, named `SOFA_HDHR_NAME 2`, `SOFA_HDHR_NAME 3` and so on, and each carries its share of the lineup.

- The lineup is split by a stable hash of each entry's ID, so a channel stays on its device when other channels change. Changing the number of ports reassigns channels across the devices.
- With a [lineup policy](lineup-policy.md#hdhomerun-devices), devices hold consecutive ranges instead: the first `SOFA_HDHR_DEVICE_SIZE` enabled channels in number order on the first device, the next on the second. Channels beyond the last device's range stay on it, and the server logs that more ports are needed.
- Each device has its own `SOFA_HDHR_TUNERS` tuners. All devices share `SOFA_MAX_SESSIONS`, so the total number of concurrent streams is still capped by it.
- With `deploy/compose.yaml` each port opens on the host itself; on a bridge network, publish every extra port. Restart after changing the list.
- Add each device to the same DVR, with `SERVER_HOST:PORT` for its port and the same XMLTV guide URL.

For two extra devices:

```text
SOFA_HDHR_EXTRA_PORTS=9194,9195
```

The extra ports must differ from `SOFA_PORT`. They are opened only when `SOFA_HDHR_ENABLED` is `true`.

## Troubleshooting

[Troubleshooting](troubleshooting.md) covers empty lineups, imports, sources, the guide, players, logos and Plex.
