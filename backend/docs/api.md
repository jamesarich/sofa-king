# API

The API uses JSON under `/api/v1`. All timestamps are UTC instants. When `SOFA_API_TOKEN` is configured, send `Authorization: Bearer $SOFA_API_TOKEN` on API requests. Routes under `/api/v1/admin` take `SOFA_ADMIN_TOKEN` instead when one is configured, sent as `Authorization: Bearer $SOFA_ADMIN_TOKEN`. The examples assume these shell variables hold the tokens and that the server runs under Docker at `SERVER_HOST:9193`; outside Docker the port is 8080. On a loopback server with no token, leave the header out. The token model is in [security](../SECURITY.md#credentials-and-the-routes-they-open).

## Endpoints

| Method | Path | Response or action |
| --- | --- | --- |
| GET | `/healthz` | Public liveness check |
| GET | `/readyz` | Public database readiness check |
| GET | `/api/v1/bootstrap` | Server ID, time, API version and capabilities |
| GET | `/api/v1/channels` | Catalog revision and the enabled lineup entries as channels; supports ETag/If-None-Match |
| GET | `/api/v1/providers` | Provider import status and channel counts |
| GET | `/api/v1/sources` | Source health and manual-disable flags for every feed source |
| PATCH | `/api/v1/sources/{id}` | Set `manuallyDisabled` |
| POST | `/api/v1/guide/query` | Programs overlapping a requested channel/time window |
| POST | `/api/v1/playback-sessions` | Create a preview or watch session |
| GET | `/api/v1/playback-sessions/{id}` | Read the current playback descriptor |
| PATCH | `/api/v1/playback-sessions/{id}` | Renew the lease and optionally change intent |
| DELETE | `/api/v1/playback-sessions/{id}` | Release the session; returns 204 |
| GET | `/media/sessions/{id}/{epoch}/{token}` | Session-authorized HLS or MPEG-TS media. `{epoch}` is the descriptor's epoch and `{token}` a random token for one playlist, segment or key |
| GET | `/output/playlist.m3u` | The enabled lineup as an M3U playlist for third-party players, see [player outputs](#player-outputs) |
| GET | `/output/guide.xml?days=` | The lineup's programs as XMLTV |
| GET | `/output/stream/{channelId}.m3u8` or `.ts` | Opens a player session for the channel and streams it |
| GET | `/logos/{key}` | A cached lineup logo, public, see [logos](#logos) |
| GET | `/discover.json`, `/lineup.json`, `/lineup_status.json`, `/device.xml` | HDHomeRun device as Plex reads it, see [HDHomeRun emulation](#hdhomerun-emulation) |
| GET, POST | `/lineup.post` | Accepts Plex's channel-scan request; answers 200 |
| GET | `/auto/v{number}` | MPEG-TS stream of the channel with that lineup number |
| GET, POST | `/admin`, `/admin/*` | The [admin portal](#admin-portal-routes), HTML |

Request bodies are limited to 64 KiB, except a policy write through `PUT /api/v1/admin/policy` or the portal's group and provider edits (4 MiB). A larger body answers `413 REQUEST_TOO_LARGE` before the token is checked. Every response carries an `X-Request-Id` header with the id the server's log lines for that request carry. Responses under `/api/` and `/admin`, the M3U playlist, the XMLTV guide and `/lineup.json` are gzip- or deflate-compressed when the request's `Accept-Encoding` allows it; media, streams and logos never are. The live TV fields are defined in the [contract](../../contract/src/commonMain/kotlin/dev/jamesrich/sofaking/contract/), which the app compiles against too, and the admin fields in the [server's models](../server/src/main/kotlin/dev/jamesrich/sofaking/backend/api/); see [shared types](#shared-types).

Channel IDs are lineup entry IDs. A channel's `displayNumber`, `name` and `tags` (the group) come from its entry; `logo` is the entry's logo served from this server as the server-relative path `/logos/KEY` (see [logos](#logos)), which a client resolves against the base URL it uses for the API, a proxy included (the server cannot be served under a sub-path); `legacyGuideId` is the mapped XMLTV channel ID; `guideStatus` is `unassigned`, `empty`, `available` or `stale`; `playbackEligible` is true when any of the entry's sources is selectable. Channels are listed in lineup order.

Each provider status reports `lastSuccessAt`, `errorCode` (`IMPORT_FAILED` when the last import kept the previous snapshot), `channels` and `sources` from the last successful import, `skippedEntries`, `skipReasons` and `notes`. Skip reasons are counts by short key, for example `duplicate-source=1, missing-name=3`: `missing-name`, `invalid-url`, `invalid-uuid`, `duplicate-source` (the same source key twice, see [source identity](live-tv.md#source-identity)), `bad-protocol` and `no-extinf`. Notes count imported entries whose identity was derived, for example `merged-key=2, url-identity=724`: `url-identity` (no `channel-id`, `tvg-id` or `channel-uuid`, so the URL is the identity) and `merged-key` (a published identity repeated with another name or number, kept as an alternative source of the first entry). Both are null when empty.

## Query the guide

Replace `CHANNEL_UUID` with a catalog ID. Set `FROM_UTC` and `TO_UTC` to ISO 8601 UTC timestamps (`YYYY-MM-DDTHH:MM:SSZ`):

```sh
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:9193/api/v1/guide/query \
  -H 'Content-Type: application/json' \
  --data '{
    "channelIds": ["CHANNEL_UUID"],
    "from": "FROM_UTC",
    "to": "TO_UTC"
  }'
```

Request 1-200 distinct channels and a positive window of at most 24 hours. Programs overlap when `startsAt < to && endsAt > from`. A disabled or unknown entry answers `404 CHANNEL_NOT_FOUND`.

Each program has `id`, `channelId`, `startsAt`, `endsAt`, `title` and `description`, and the metadata its guide source provides: `subTitle` (the episode title), `episode` (a label such as `S2 E5` or `E5`, or the source's on-screen numbering), `isNew` (a first airing), `rating` (such as `TV-14`) and `icon` (an image URL on the source's host). A field the source does not provide is null, or false for `isNew`. Clients should ignore fields they do not know.

To page through a consistent guide, pass the returned `revision` in subsequent requests. A changed revision (any guide re-imported, or the lineup changed) returns `409 RESYNC_REQUIRED`; query again without the old revision.

## Open and release playback

Create a session with a catalog channel ID and a unique idempotency key:

```sh
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:9193/api/v1/playback-sessions \
  -H 'Content-Type: application/json' \
  --data '{
    "channelId": "CHANNEL_UUID",
    "intent": "preview",
    "idempotencyKey": "UNIQUE_REQUEST_KEY"
  }'
```

The server picks the entry's first selectable source: healthy before unverified before suspect, lineup order within a rank. The response contains `id`, `expiresAt`, and a `descriptor` with `protocol`, `mimeType`, relative `url`, and `epoch`. Resolve the URL against the server address and give it to the player.

The descriptor's `protocol`, `mimeType` and URL suffix (`.m3u8` or `.ts`) come from the source's protocol, not from the upstream path. That protocol is the one a probe or media fetch detected in the stream body and stored in the database; a source without a detection yet uses the import's protocol. If a media fetch finds the other protocol (an M3U behind an extensionless URL, or MPEG-TS behind a `.m3u8` path), the server stores it, increments the epoch and answers `410 STREAM_CHANGED`; fetch the session and open the new URL. Every later session on that source, including after a restart, starts with the detected protocol.

A watch session expires 90 seconds after its last renewal, a preview after 45 seconds. When `SOFA_MAX_SESSIONS` sessions are active, a new request evicts the oldest preview session (its media requests end and later calls on it answer `410 SESSION_EXPIRED`); if every active session is a watch, the request fails with `429 CAPACITY_EXHAUSTED`. Renew and promote a preview without replacing its stream:

```sh
curl --fail -X PATCH \
  -H "Authorization: Bearer $SOFA_API_TOKEN" \
  http://SERVER_HOST:9193/api/v1/playback-sessions/SESSION_ID \
  -H 'Content-Type: application/json' \
  --data '{"intent":"watch"}'
```

Send `{}` to renew without changing intent. Fetch the session periodically; if its descriptor epoch changes, reopen the returned media URL. When the current source stops being selectable, the fetch moves the session to the entry's next selectable source with a new epoch.

Release the session when playback ends:

```sh
curl --fail -X DELETE \
  -H "Authorization: Bearer $SOFA_API_TOKEN" \
  http://SERVER_HOST:9193/api/v1/playback-sessions/SESSION_ID
```

Release is idempotent and cancels active upstream requests. Repeating an open request with the same key returns the same session while it remains active. A reused key with different parameters returns `409 IDEMPOTENCY_CONFLICT`.

## Player outputs

`/output/*` serves the lineup to players that only speak HTTP, such as M3U and XMLTV players. They send no headers, so these routes take `SOFA_OUTPUT_TOKEN` as `?token=` in the URL, or as a bearer; the API token is accepted as a bearer only, never in a query. With neither token configured (a loopback development server) the routes are open. A URL token lands in player configuration, screenshots and any proxy log between the player and the server, which is why it is a separate token that opens nothing but these routes; this server never logs a query string. Rotate it by changing the variable and re-adding the playlist in each player. Without `SOFA_OUTPUT_TOKEN` the routes answer `401 AUTH_REQUIRED` to anything but the API bearer.

`GET /output/playlist.m3u` lists the enabled lineup in order, `audio/x-mpegurl`:

```text
#EXTM3U url-tvg="http://HOST/output/guide.xml?token=TOKEN" x-tvg-url="http://HOST/output/guide.xml?token=TOKEN"
#EXTINF:-1 tvg-id="CHANNEL_UUID" tvg-chno="5" tvg-name="Station" tvg-logo="https://..." group-title="News",Station
http://HOST/output/stream/CHANNEL_UUID.m3u8?token=TOKEN
```

`tvg-id` is the channel ID, the same value the XMLTV uses, so players match guide and channel without a name lookup. `HOST` is the `Host` header of the request, so the URLs work through a port mapping; `X-Forwarded-Proto: https` switches the scheme. The stream suffix follows the protocol of the source the channel would play now: `.m3u8` for HLS, `.ts` for MPEG-TS and for a channel without an eligible source, which lists but fails at play time. After a source's protocol is detected (a probe or a fetch), the suffix changes on the player's next playlist refresh.

`GET /output/guide.xml` is XMLTV: one `<channel id="CHANNEL_UUID">` per enabled channel with the name and the number as `<display-name>` values and the logo as `<icon>`, then `<programme>` elements from three hours ago to `days` ahead (`SOFA_XMLTV_DAYS`, default 3; `?days=1`-`14` overrides). Times are `YYYYMMDDHHMMSS +0000`.

Each program carries the metadata its guide source provides, in XMLTV order: `<title>`, `<sub-title>`, `<desc>`, `<date>`, `<category>`, `<icon>`, every `<episode-num>` the source gave with its own `system` (`xmltv_ns`, `onscreen`, `dd_progid` and others), `<previously-shown>` with its `start` when known, `<new/>` and `<rating>` with its `system` when known. `<title>`, `<sub-title>`, `<desc>` and the source's categories carry `lang` when the source named the title's language. `<icon>` is the source's own image URL, not one served from this server. A source XMLTV keeps at most 8 categories, 8 episode numbers and the first rating per programme.

The categories are the source's own, with any kind Plex sorts by (`Movie`, `Sports`, `News`, `Children`, `Series`) first. A program whose source gave none carries its channel's group instead, with `lang="en"`: first the kind Plex sorts by, then the group's own name when it differs. The kind is `Movie` for Movies, `Sports` for Sports, `News` for News, Local and Local News (US), `Children` for Kids & Family, and `Series` for anything else.

A program that is not a movie and has no episode numbering from its source gets `<episode-num system="original-air-date">` with its start time in UTC, `YYYY-MM-DD HH:MM:SS`, since Plex files a program without episode numbering as a movie. A program counts as a movie when its channel is in Movies or a category is `Movie`. A channel with no program in the window, guide mapping or not, gets hourly placeholder programs titled with its name, with the group's categories and that air date, so Plex lists and records it like the rest. The body is streamed.

`tvg-logo` and `<icon>` name the channel's logo at `http://HOST/logos/KEY`, never the feed's own logo host.

`GET /output/stream/{channelId}.m3u8|.ts` opens a watch session for the channel and plays it. An HLS source answers `302` to the session's playlist URL under `/media/sessions/`; the player follows it and fetches playlists, segments and keys through the relay. An MPEG-TS source streams `video/mp2t` in this response through the fan-out. Players never renew, so these sessions live differently from API sessions. A request in flight holds the session, and it is released 60 seconds after its last media request (the player stopped) or when a TS player disconnects. Two players on the same HLS channel share one session; each TS request is its own. At `SOFA_MAX_SESSIONS` the oldest preview session yields first, then an idle player session (a zap left behind), and otherwise the request answers `429 CAPACITY_EXHAUSTED`.

When an unprobed source's descriptor says MPEG-TS and the body is HLS, the request switches to the redirect itself instead of answering `410`. The reverse, an HLS descriptor with an MPEG-TS body, reaches the player as `410` on the media URL once; after that the stored protocol is right. A disabled or unknown channel answers `404 CHANNEL_NOT_FOUND`, and a channel with no eligible source `503 SOURCE_UNAVAILABLE`.

## Logos

Every logo URL a lineup entry carries (its own `logo`, else the first source's `tvg-logo`) is served from this server as `/logos/KEY`, where `KEY` is the lowercase hex SHA-256 of the source URL as the entry or feed carries it. References are normalized when a playlist, guide or import is read (whitespace trimmed, unencoded characters percent-encoded, relative paths resolved against the playlist or guide URL) and the fetch normalizes a stored URL the same way. `/api/v1/channels` names that path alone; the M3U `tvg-logo` and the XMLTV `<icon>` name it with the request's `Host`, since players fetch them as absolute URLs. The route takes no token: players, Plex and browsers fetch logos with no credentials, and a key is only ever the hash of a URL the lineup names, so nothing else is fetched or served through it.

The server fetches each logo source once and keeps the file under `SOFA_DATA_DIR/logos/`. It fetches in the background after an import or lineup change, four at a time, or within the first request for a key nothing has fetched yet. A fetch follows the [upstream address policy](live-tv.md#upstream-addresses) and sends no provider headers. It must answer `200` with a `Content-Type` of `image/png`, `image/jpeg`, `image/webp`, `image/gif` or `image/svg+xml`, and a body of at most 2 MiB whose bytes are that kind of image; anything else is refused. The signature decides the stored type, and an SVG must be XML. A PNG or JPEG wider or taller than 512 pixels is stored scaled down to fit 512 by 512, keeping its proportions and a PNG's transparency. A smaller one, a GIF or WebP, and one that would not get smaller are stored as fetched. An SVG is stored and served byte for byte with a `Content-Security-Policy` that forbids scripts.

A cached logo answers `200` with its type, `ETag` (the content hash; `If-None-Match` answers `304`), `Cache-Control: public, max-age=86400` and `X-Content-Type-Options: nosniff`. An unknown key, a refused source or a source that has never answered an image answers `404 RESOURCE_NOT_FOUND`; the listing keeps the URL, the player shows its placeholder, and the server tries the source again an hour later. A logo is refreshed a week after its last fetch, on the next request or sweep; a refresh that fails keeps the stored file. A changed source URL is a new key, so the outputs switch at once. The next sweep deletes the old file, as it deletes every logo the lineup does not name.

The admin API (`/api/v1/admin/lineup`, `/api/v1/admin/candidates`) keeps the source URLs, which admins edit.

## HDHomeRun emulation

With `SOFA_HDHR_ENABLED` the root serves what a Plex server reads from an HDHomeRun tuner. The server binary enables it by default; `deploy/compose.yaml` disables it unless `deploy/.env` sets `SOFA_HDHR_ENABLED=true`. Nothing here takes a token, because Plex sends none; `SOFA_HDHR_ALLOW` (client IPs or CIDR blocks) answers everyone else `403`. Absolute URLs derive from the request's `Host` header like the player outputs.

| Path | Body |
| --- | --- |
| `/discover.json` | `FriendlyName` (`SOFA_HDHR_NAME`), `Manufacturer` `Silicondust`, `ManufacturerURL` `https://www.silicondust.com/`, `ModelNumber` `HDTC-2US`, `FirmwareName` `hdhomeruntc_atsc`, `FirmwareVersion`, `DeviceID`, `DeviceAuth`, `BaseURL`, `LineupURL`, `TunerCount` (`SOFA_HDHR_TUNERS`) |
| `/lineup.json` | One `{"GuideNumber", "GuideName", "URL"}` per enabled lineup entry in lineup order, with the entry number, name and `BASE/auto/vNUMBER`; a second entry with the same number is left out |
| `/lineup_status.json` | `{"ScanInProgress":0,"ScanPossible":0,"Source":"Cable","SourceList":["Cable"]}` |
| `/lineup.post` | `200` with no body; there is nothing to scan |
| `/device.xml` | UPnP root description with the same name, model, `DeviceID` as `serialNumber`, and a `UDN` |
| `/auto/v{number}` | `video/mp2t` for the enabled entry with that number; `404` when none |

The first device, on `SOFA_PORT`, has `SOFA_HDHR_DEVICE_ID` as its `DeviceID`, or eight hex digits derived from the server identity, and the server identity as its `UDN`. Each device on a port from `SOFA_HDHR_EXTRA_PORTS` is named `SOFA_HDHR_NAME 2`, `SOFA_HDHR_NAME 3` and so on. Its `DeviceID` is derived from the first device's and its position, and its `UDN` is a UUID derived from the server identity and its position. All of them stay the same across restarts.

`/auto/v{number}` opens an external session like a player stream URL. An MPEG-TS source goes through the fan-out. An HLS source is remuxed by FFmpeg (`-c copy -f mpegts -mpegts_flags +resend_headers`). FFmpeg reads the source URL itself, with the provider's headers when they are portable, over `http`, `https`, `tcp`, `tls` and `crypto` only. It takes one video and one audio stream, the highest resolution and the most channels, so other variants are not downloaded. It drops subtitle and data tracks and copies the elementary streams, so CEA-608 captions inside the video pass through. The process is killed and reaped when Plex disconnects or the session ends, and every remux is killed on shutdown. While `SOFA_HDHR_TUNERS` streams are open, or when the session cap or the source refuses one, the request answers `503` with `X-HDHomeRun-Error: 805 All Tuners In Use`, which Plex reports as a busy tuner. A protocol detected on the first fetch of an MPEG-TS descriptor switches to the remux within the request.

## Admin API

JSON only, under `/api/v1/admin`. With `SOFA_ADMIN_TOKEN` set, send that token; otherwise the API token applies. No response carries a stream URL.

| Method | Path | Response or action |
| --- | --- | --- |
| GET | `/api/v1/admin/status` | One summary of the server, described [below](#server-status) |
| GET | `/api/v1/admin/candidates?q=&provider=&limit=&offset=` | Feed channels with their sources (`id`, `protocol`, `status`, `manuallyDisabled`) and the lineup entries using them; `q` matches name, exact number or `tvg-id`, case-insensitively; `limit` 1-1000 (default 100) |
| GET | `/api/v1/admin/sessions` | Live playback sessions: `id`, `channelId`, `intent`, `external` (an M3U or HDHomeRun player), `busy` (a media request in flight), `providerId` of the source playing, `expiresAt`. An empty list means nothing is playing |
| GET | `/api/v1/admin/guides` | Guide import status: `lastSuccessAt`, `errorCode`, `channels`, retained `programmes` |
| GET | `/api/v1/admin/guides/{id}/channels?q=&limit=` | XMLTV channels of a guide (`channelId`, `displayName`, `icon`) |
| GET | `/api/v1/admin/lineup` | Every entry in lineup order |
| POST | `/api/v1/admin/lineup` | Create an entry; returns 201 |
| GET | `/api/v1/admin/lineup/{id}` | One entry |
| PATCH | `/api/v1/admin/lineup/{id}` | Update an entry; with a policy, writes the station override instead and applies (`If-Match` optional) |
| DELETE | `/api/v1/admin/lineup/{id}` | Remove an entry; returns 204. With a policy, writes `enabled: false` into the station override instead |
| PUT | `/api/v1/admin/lineup/order` | Reorder: `{"ids": [...]}` listing every entry once; returns the lineup |
| POST | `/api/v1/admin/lineup/adopt` | `{"providerId": "...", "enabled": true}` adds every channel of the provider whose sources no entry uses; returns `added` and `skipped` |
| GET | `/api/v1/admin/policy` | The [lineup policy](lineup-policy.md) the server runs with (`SOFA_POLICY`, or `policy.json` in the data directory), as `policy` with `revision` (the file's SHA-256, also the `ETag`) and `path`; 404 `POLICY_NOT_CONFIGURED` without one |
| PUT | `/api/v1/admin/policy` | Replace the policy with the body, up to 4 MiB; `If-Match: <revision>` required (428 `PRECONDITION_REQUIRED` without it, 409 `POLICY_CHANGED` when stale or the file changed on disk, 400 `INVALID_POLICY` with every error). Writes atomically, applies, and returns `document` and `apply` |
| GET | `/api/v1/admin/policy/preview` | What a run would change against the served lineup; writes nothing. Fields are [below](#policy-results) |
| POST | `/api/v1/admin/policy/apply` | Run the policy now: writes the lineup when it differs, logs each change, and writes assigned numbers and learned aliases back to the file. Fields are [below](#policy-results) |
| GET | `/api/v1/admin/policy/findings` | Open findings of a run over the current feeds. Fields are [below](#policy-results) |
| GET | `/api/v1/admin/policy/changes?limit=` | What policy runs changed, newest first, as `{"id", "appliedAt", "change"}`: `change` has the station `key`, `entryId`, `entryName`, `field`, `before`, `after` and the run's cause in `reason`. `limit` 1-1000 (default 100); the server keeps the newest 10,000 |
| GET | `/api/v1/admin/stations/{key}` | One station key: the station the policy builds, its pins, and every candidate that names the key or joins it, each `taken`, `skipped` or `claimed` with a `reason` |
| GET | `/api/v1/admin/plex` | The last [Plex sync](live-tv.md#keep-plex-in-step): `syncMinutes`, `lastSyncAt`, `lastCause` (`startup`, `policy`, `interval` or `api`), `lastError`, `dvrs` whose guide it reloaded, `devices` (`deviceId`, `device`, `plexKey`, `dvr`, `mapped`, `added`), `missingDevices` and `findings`. It survives a restart; before any sync, `lastSyncAt` is null. 404 `PLEX_NOT_CONFIGURED` when `SOFA_PLEX_URL` is unset |
| POST | `/api/v1/admin/plex/sync` | Sync Plex now; returns the same status. A failure is reported in `lastError`, not as an error response |

While a lineup policy is in use, `POST /lineup`, `PUT /lineup/order` and `POST /lineup/adopt` answer `410 Gone` with code `POLICY_MANAGED`; see [lineup policy](lineup-policy.md#api). Without it, the `/policy/*` routes answer `404 POLICY_NOT_CONFIGURED`.

### Server status

`GET /api/v1/admin/status` answers one JSON document, defined in the [server's status model](../server/src/main/kotlin/dev/jamesrich/sofaking/backend/api/Status.kt):

| Field | Contents |
| --- | --- |
| `revision` | The commit the image was built from, from `SOFA_REVISION`; `unknown` outside the image |
| `startedAt`, `uptimeSeconds` | When the server started, and the seconds since |
| `schemaVersion` | The newest database migration applied |
| `channels` | Lineup entries: `total`, `enabled`, and `eligible`, the enabled ones with a source eligible for playback |
| `sources` | Feed sources: `total`, `byStatus` with a count for each health status, and `manuallyDisabled` |
| `providers`, `guides` | `total`, `pending` (waiting for a first import), `lastImportAt` (the newest successful import), and `failing`, one entry per failed import with `id`, `errorCode` and `lastSuccessAt` |
| `policy` | With a lineup policy: its `revision`, and `lastRunAt` and `lastRunChanged` of the last apply that built the lineup. Null without one |
| `plex` | With Plex sync: `lastSyncAt`, `mapped` (channels mapped across all devices) and `lastError`. Null without it |
| `activeSessions` | Live playback sessions |

```sh
curl --fail -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" http://SERVER_HOST:9193/api/v1/admin/status
```

### Policy results

The [server's policy models](../server/src/main/kotlin/dev/jamesrich/sofaking/backend/api/Policy.kt) define these responses.

| Response | Fields |
| --- | --- |
| Preview | `generatedAt`, `revision`, `stations`, `enabledStations`, `added`, `removed`, `changes`, `merges`, `splits`, `counts`, `findings`, `suggestedAliases`, `learned`, `devicesNeeded`, `devicesConfigured`, `unmappedCategories` |
| Apply | `appliedAt`, `revision`, `changed`, `stations`, `enabledStations`, `changes`, `assigned`, `findings`, `suggestedAliases`, `learned`, `devicesNeeded`, `devicesConfigured`, `unmappedCategories` |
| Findings | `generatedAt`, `revision`, `findings`, `suggestedAliases`, `unmappedCategories` |

- `stations` and `enabledStations` count the stations the run builds and those it turns on.
- In a preview, `added` lists the new station keys. `removed` lists the entries the run drops, each as `{"key", "entryId", "name"}`. `changes` lists each field that differs, as `{"key", "entryId", "field", "before", "after"}`. `merges` and `splits` list entries that join one station or spread over several, as `{"keys", "entryIds"}`, and `counts` maps a name to a count.
- In an apply, `changed` says whether the lineup was written, `changes` is the number of fields that changed, and `assigned` maps each newly numbered station key to its number.
- `learned` maps the aliases the run found from matching schedules, from key to station key.
- `devicesNeeded` and `devicesConfigured` are set with HDHomeRun range devices, and null otherwise.
- `unmappedCategories` lists the raw categories no group mapping names, each as `{"provider", "category", "stations", "example"}`.

An entry has `id`, `position`, `number`, `name`, `group`, `logo`, `enabled`, `guide` (`{"guideId", "channelId"}` or null), `offerings`, `guideStatus` and `createdBy` (`policy` for an entry the policy built, else `manual`). `offerings` lists the entry's sources in failover order, each with `id`, `providerId`, `channelName`, `protocol`, `status` and `manuallyDisabled`. `status` is a health state, or `WITHDRAWN` when no feed lists the source now. While the policy holds a station off for want of an eligible source, the entry also has `autoDisabledAt` and `autoDisabledReason` (`unavailable`).

Create from a feed candidate, copying its number, name, logo, group, sources and (when its `tvg-id` is listed by a configured guide) guide mapping:

```sh
curl --fail -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" http://SERVER_HOST:9193/api/v1/admin/lineup \
  -H 'Content-Type: application/json' \
  --data '{"channelId": "FEED_CHANNEL_ID"}'
```

Any field in the body overrides the copied value: `id` (a UUID), `number`, `name`, `group`, `logo`, `enabled` (true when absent), `offerings` (source IDs in failover order), `guide`, `position` (insertion index). Without `channelId`, `number` and `name` are required.

Update fields with `PATCH`; absent fields keep their value, `clearGroup`, `clearLogo` and `clearGuide` remove optional values, `position` moves the entry. Sending `enabled` clears `autoDisabledAt` and `autoDisabledReason`.

```sh
curl --fail -X PATCH -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" http://SERVER_HOST:9193/api/v1/admin/lineup/ENTRY_ID \
  -H 'Content-Type: application/json' \
  --data '{"number": "501", "offerings": ["SOURCE_ID", "FALLBACK_SOURCE_ID"], "guide": {"guideId": "gracenote", "channelId": "XMLTV_ID"}}'
```

Validation: `number` 1-32 characters without spaces, `name` 1-256, `group` 1-128, `logo` an HTTP(S) URL, at most 64 sources per entry, every source ID known to a configured feed, `guideId` a configured guide. An active session keeps its current source until that source stops being selectable or the session ends.

## Admin portal routes

The [admin portal](admin.md) is HTML under `/admin`. Every route but the sign-in page, the sign-in form and the icon needs the `sofa_admin` session cookie. A page request without one is redirected to `/admin`, and an action answers `401`. Every `POST` but the sign-in also needs the session's CSRF token, as the form field the pages include or the `X-CSRF-Token` header, and answers `403` without it.

| Method | Path | Page or action |
| --- | --- | --- |
| GET | `/admin` | The sign-in page, or the overview once signed in |
| POST | `/admin/login` | Sign in with the form field `token`. 10 attempts a minute per client address, then `429` |
| POST | `/admin/logout` | Sign out |
| GET | `/admin/static/icon.svg`, `/admin/static/htmx.min.js` | The portal's icon (public) and htmx (signed in) |
| GET | `/admin/lineup` | Channels, filtered by the query |
| GET | `/admin/lineup/{id}` | One channel |
| POST | `/admin/lineup/{id}/visibility`, `/admin/lineup/{id}/edit` | Hide or show a channel; rename, renumber or regroup it |
| GET | `/admin/merges` | Suggested and learned aliases |
| POST | `/admin/merges/accept`, `/admin/merges/dismiss`, `/admin/merges/never` | Accept a suggestion, dismiss it, or keep a learned pair apart |
| GET | `/admin/required` | Required stations |
| GET, POST | `/admin/providers`, `/admin/providers/edit` | Provider entries and their edits |
| GET, POST | `/admin/groups`, `/admin/groups/edit` | Groups and their edits |
| GET, POST | `/admin/plex`, `/admin/plex/reveal` | Plex and the device addresses; reveal the output token |
| GET | `/admin/settings?changes=` | Settings and the newest policy changes, 50 by default |
| POST | `/admin/settings/reveal-api-token` | Reveal the API token |
| POST | `/admin/policy/apply` | Apply the lineup policy now |

## Shared types

The live TV requests and responses above (`/api/v1/bootstrap`, `/api/v1/channels`, `/api/v1/guide/query`, `/api/v1/playback-sessions`) and the error body are Kotlin types in the [contract](../../contract/README.md) build, which the server and the Sofa King app both compile against. `Wire` in the same package names the intents, guide statuses, error codes and guide limits clients act on.

The server keeps clients older and newer than itself working: a response keeps every field of the frozen fixtures in `contract/src/jvmTest/resources/wire`, a field no client reads has a default, and the guide query and playback session requests ignore fields the server does not know. Admin requests reject unknown fields.

## Errors

Errors contain `code`, `message` and `retryable`:

```json
{
  "code": "SOURCE_UNAVAILABLE",
  "message": "No eligible source",
  "retryable": true
}
```

| Status | Codes |
| --- | --- |
| 400 | `INVALID_REQUEST` (a malformed body or path), `INVALID_QUERY` (a guide query or `days` out of range), `INVALID_POLICY` (a policy write that fails validation, with every error in `message`) |
| 401 | `AUTH_REQUIRED` |
| 404 | `CHANNEL_NOT_FOUND`, `SOURCE_NOT_FOUND`, `LINEUP_ENTRY_NOT_FOUND`, `GUIDE_NOT_FOUND`, `PROVIDER_NOT_FOUND`, `RESOURCE_NOT_FOUND` (an unknown route, logo or media resource), `POLICY_NOT_CONFIGURED`, `PLEX_NOT_CONFIGURED` |
| 409 | `RESYNC_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `LINEUP_ENTRY_EXISTS`, `POLICY_CHANGED` (a stale `If-Match` or a policy file changed on disk), `STATION_CONFLICT` (a merge of two stations that both have overrides) |
| 410 | `SESSION_EXPIRED`, `STREAM_CHANGED`, `POLICY_MANAGED` |
| 413 | `REQUEST_TOO_LARGE`, answered before the token is checked |
| 422 | `FORMAT_UNSUPPORTED` (an HLS playlist with variables, content steering or a non-HTTP resource) |
| 428 | `PRECONDITION_REQUIRED` (a policy write without `If-Match`) |
| 429 | `CAPACITY_EXHAUSTED` |
| 500 | `INTERNAL_ERROR` |
| 502 | `UPSTREAM_UNAVAILABLE`, `UPSTREAM_REJECTED`, `INVALID_MANIFEST` |
| 503 | `NOT_READY`, `SOURCE_UNAVAILABLE` |

The admin portal shows its errors on its pages. A sign-in over its limit answers `429 Too Many Requests` with no error body.
