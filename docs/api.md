# API

The API uses JSON under `/api/v1`. All timestamps are UTC instants. When `SOFA_API_TOKEN` is configured, send `Authorization: Bearer TOKEN` on API requests. Routes under `/api/v1/admin` take `SOFA_ADMIN_TOKEN` instead when one is configured.

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
| GET | `/media/sessions/{id}/{epoch}/{resource}` | Session-authorized HLS or MPEG-TS media |
| GET | `/output/playlist.m3u` | The enabled lineup as an M3U playlist for third-party players, see [player outputs](#player-outputs) |
| GET | `/output/guide.xml?days=` | The lineup's programmes as XMLTV |
| GET | `/output/stream/{channelId}.m3u8` or `.ts` | Opens a player session for the channel and streams it |
| GET | `/logos/{key}` | A cached lineup logo, public, see [logos](#logos) |
| GET | `/discover.json`, `/lineup.json`, `/lineup_status.json`, `/device.xml` | HDHomeRun device as Plex reads it, see [HDHomeRun emulation](#hdhomerun-emulation) |
| GET, POST | `/lineup.post` | Accepts Plex's channel-scan request; answers 200 |
| GET | `/auto/v{number}` | MPEG-TS stream of the channel with that lineup number |

Request bodies are limited to 64 KiB, except the lineup import (16 MiB). The [contract module](../api-contract/src/main/kotlin/dev/jamesrich/sofaking/backend/api/) defines the response fields.

Channel IDs are lineup entry IDs. A channel's `displayNumber`, `name` and `tags` (the group) come from its entry; `logo` is the entry's logo served from this server, `http://HOST/logos/KEY` (see [logos](#logos)), with `HOST` taken from the request's `Host` header; `legacyGuideId` is the mapped XMLTV channel ID; `guideStatus` is `unassigned`, `empty`, `available` or `stale`; `playbackEligible` is true when any of the entry's sources is selectable. Channels are listed in lineup order.

Each provider status reports `lastSuccessAt`, `errorCode` (`IMPORT_FAILED` when the last import kept the previous snapshot), `channels` and `sources` from the last successful import, `skippedEntries`, `skipReasons` and `notes`. Skip reasons are counts by short key, for example `duplicate-source=1, missing-name=3`: `missing-name`, `invalid-url`, `invalid-uuid`, `duplicate-source` (the same source key twice, see [source identity](live-tv.md#source-identity)), `bad-protocol` and `no-extinf`. Notes count imported entries whose identity was derived, for example `merged-key=2, url-identity=724`: `url-identity` (no `channel-id`, `tvg-id` or `channel-uuid`, so the URL is the identity) and `merged-key` (a published identity repeated with another name or number, kept as an alternative source of the first entry). Both are null when empty.

## Query the guide

Replace `CHANNEL_UUID` with a catalog ID. Set `FROM_UTC` and `TO_UTC` to ISO 8601 UTC timestamps (`YYYY-MM-DDTHH:MM:SSZ`):

```sh
curl --fail http://127.0.0.1:8080/api/v1/guide/query \
  -H 'Content-Type: application/json' \
  --data '{
    "channelIds": ["CHANNEL_UUID"],
    "from": "FROM_UTC",
    "to": "TO_UTC"
  }'
```

Request 1–200 distinct channels and a positive window of at most 24 hours. Programs overlap when `startsAt < to && endsAt > from`. A disabled or unknown entry answers `404 CHANNEL_NOT_FOUND`.

To page through a consistent guide, pass the returned `revision` in subsequent requests. A changed revision (any guide re-imported, or the lineup changed) returns `409 RESYNC_REQUIRED`; query again without the old revision.

## Open and release playback

Create a session with a catalog channel ID and a unique idempotency key:

```sh
curl --fail http://127.0.0.1:8080/api/v1/playback-sessions \
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
  http://127.0.0.1:8080/api/v1/playback-sessions/SESSION_ID \
  -H 'Content-Type: application/json' \
  --data '{"intent":"watch"}'
```

Send `{}` to renew without changing intent. Fetch the session periodically; if its descriptor epoch changes, reopen the returned media URL. When the current source stops being selectable, the fetch moves the session to the entry's next selectable source with a new epoch.

Release the session when playback ends:

```sh
curl --fail -X DELETE \
  http://127.0.0.1:8080/api/v1/playback-sessions/SESSION_ID
```

Release is idempotent and cancels active upstream requests. Repeating an open request with the same key returns the same session while it remains active. A reused key with different parameters returns `409 IDEMPOTENCY_CONFLICT`.

## Player outputs

`/output/*` serves the lineup to players that only speak HTTP (TiviMate, VLC). They send no headers, so these routes take `SOFA_OUTPUT_TOKEN` as `?token=` in the URL, or as a bearer; the API token is accepted as a bearer only, never in a query. With neither token configured (a loopback development server) the routes are open. A URL token lands in player configuration, screenshots and any proxy log between the player and the server, which is why it is a separate token that opens nothing but these routes; this server never logs request URLs. Rotate it by changing the variable and re-adding the playlist in each player. Without `SOFA_OUTPUT_TOKEN` the routes answer `401 AUTH_REQUIRED` to anything but the API bearer.

`GET /output/playlist.m3u` lists the enabled lineup in order, `audio/x-mpegurl`:

```text
#EXTM3U url-tvg="http://HOST/output/guide.xml?token=TOKEN" x-tvg-url="http://HOST/output/guide.xml?token=TOKEN"
#EXTINF:-1 tvg-id="CHANNEL_UUID" tvg-chno="5" tvg-name="Station" tvg-logo="https://..." group-title="News",Station
http://HOST/output/stream/CHANNEL_UUID.m3u8?token=TOKEN
```

`tvg-id` is the channel ID, the same value the XMLTV uses, so players match guide and channel without a name lookup. `HOST` is the `Host` header of the request, so the URLs work through a port mapping; `X-Forwarded-Proto: https` switches the scheme. The stream suffix follows the protocol of the source the channel would play now: `.m3u8` for HLS, `.ts` for MPEG-TS and for a channel without an eligible source, which lists but fails at play time. After a source's protocol is detected (a probe or a fetch), the suffix changes on the player's next playlist refresh.

`GET /output/guide.xml` is XMLTV: one `<channel id="CHANNEL_UUID">` per enabled channel with the name and the number as `<display-name>` values and the logo as `<icon>`, then `<programme>` elements from three hours ago to `days` ahead (`SOFA_XMLTV_DAYS`, default 3; `?days=1`–`14` overrides). Times are `YYYYMMDDHHMMSS +0000`. Only channels with a guide mapping have programmes. The body is streamed and uncompressed.

`tvg-logo` and `<icon>` name the channel's logo at `http://HOST/logos/KEY`, never the feed's own logo host.

`GET /output/stream/{channelId}.m3u8|.ts` opens a watch session for the channel and plays it. An HLS source answers `302` to the session's playlist URL under `/media/sessions/`; the player follows it and fetches playlists, segments and keys through the relay. An MPEG-TS source streams `video/mp2t` in this response through the fan-out. Players never renew, so these sessions live differently from API sessions: a request in flight holds the session, and it is released 60 seconds after its last media request (the player stopped) or when a TS player disconnects. Two players on the same HLS channel share one session; each TS request is its own. At `SOFA_MAX_SESSIONS` the oldest preview session yields first, then an idle player session (a zap left behind), and otherwise the request answers `429 CAPACITY_EXHAUSTED`. When an unprobed source's descriptor says MPEG-TS and the body is HLS, the request switches to the redirect itself instead of answering `410`; the reverse (HLS descriptor, MPEG-TS body) reaches the player as `410` on the media URL once, after which the stored protocol is right. A disabled or unknown channel answers `404 CHANNEL_NOT_FOUND`; a channel with no eligible source `503 SOURCE_UNAVAILABLE`.

## Logos

Every logo URL a lineup entry carries (its own `logo`, else the first source's `tvg-logo`) is served from this server as `/logos/KEY`, where `KEY` is the lowercase hex SHA-256 of the source URL as the entry or feed carries it. References are normalized when a playlist, guide or import is read (whitespace trimmed, unencoded characters percent-encoded, relative paths resolved against the playlist or guide URL) and the fetch normalizes a stored URL the same way. `/api/v1/channels`, the M3U `tvg-logo` and the XMLTV `<icon>` all name that path with the request's `Host`. The route takes no token: players, Plex and browsers fetch logos with no credentials, and a key is only ever the hash of a URL the lineup names, so nothing else is fetched or served through it.

The server fetches each source once, in the background after an import or lineup change (four at a time) or within the first request for a key nothing has fetched yet, and keeps the file under `SOFA_DATA_DIR/logos/`. A fetch follows the [upstream address policy](live-tv.md#upstream-addresses) and sends no provider headers; it must answer `200` with a `Content-Type` of `image/png`, `image/jpeg`, `image/webp`, `image/gif` or `image/svg+xml`, a body of at most 2 MiB whose bytes are that kind of image (the signature decides the stored type; an SVG must be XML), and anything else is refused. An SVG is stored and served byte for byte with a `Content-Security-Policy` that forbids scripts.

A cached logo answers `200` with its type, `ETag` (the content hash; `If-None-Match` answers `304`), `Cache-Control: public, max-age=86400` and `X-Content-Type-Options: nosniff`. An unknown key, a refused source or a source that has never answered an image answers `404 RESOURCE_NOT_FOUND`; the listing keeps the URL, the player shows its placeholder, and the server tries the source again an hour later. A logo is refreshed a week after its last fetch, on the next request or sweep; a refresh that fails keeps the stored file. A changed source URL is a new key, so the outputs switch at once and the old file is deleted by the next sweep, like every logo the lineup no longer names.

The admin API (`/api/v1/admin/lineup`, `/api/v1/admin/candidates`) keeps the source URLs, which is what the household edits.

## HDHomeRun emulation

With `SOFA_HDHR_ENABLED` (the default) the root serves what a Plex server reads from an HDHomeRun tuner. Nothing here takes a token, because Plex sends none; `SOFA_HDHR_ALLOW` (client IPs or CIDR blocks) answers everyone else `403`. Absolute URLs derive from the request's `Host` header like the player outputs.

| Path | Body |
| --- | --- |
| `/discover.json` | `FriendlyName` (`SOFA_HDHR_NAME`), `Manufacturer`, `ModelNumber` `HDTC-2US`, `FirmwareName` `hdhomeruntc_atsc`, `FirmwareVersion`, `DeviceID` (`SOFA_HDHR_DEVICE_ID`, else eight hex digits derived from the server identity), `DeviceAuth`, `BaseURL`, `LineupURL`, `TunerCount` (`SOFA_HDHR_TUNERS`) |
| `/lineup.json` | One `{"GuideNumber", "GuideName", "URL"}` per enabled lineup entry in lineup order, with the entry number, name and `BASE/auto/vNUMBER`; a second entry with the same number is left out |
| `/lineup_status.json` | `{"ScanInProgress":0,"ScanPossible":0,"Source":"Cable","SourceList":["Cable"]}` |
| `/lineup.post` | `200` with no body; there is nothing to scan |
| `/device.xml` | UPnP root description with the same name, model, `DeviceID` as `serialNumber` and the server identity as `UDN` |
| `/auto/v{number}` | `video/mp2t` for the enabled entry with that number; `404` when none |

`/auto/v{number}` opens an external session like a player stream URL. An MPEG-TS source goes through the fan-out. An HLS source is remuxed by FFmpeg (`-c copy -f mpegts -mpegts_flags +resend_headers`): FFmpeg reads the source URL itself with the provider's headers when they are portable, takes one video and one audio stream (the highest resolution and the most channels, so other variants are not downloaded), drops subtitle and data tracks, and copies the elementary streams, so CEA-608 captions inside the video pass through. The process is killed and reaped when Plex disconnects or the session ends, and every remux is killed on shutdown. While `SOFA_HDHR_TUNERS` streams are open, or when the session cap or the source refuses one, the request answers `503` with `X-HDHomeRun-Error: 805 All Tuners In Use`, which Plex reports as a busy tuner. A protocol detected on the first fetch of an MPEG-TS descriptor switches to the remux within the request.

## Admin API

JSON only, under `/api/v1/admin`. With `SOFA_ADMIN_TOKEN` set, send that token; otherwise the API token applies. No response carries a stream URL.

| Method | Path | Response or action |
| --- | --- | --- |
| GET | `/api/v1/admin/candidates?q=&provider=&limit=&offset=` | Feed channels with their sources (`id`, `protocol`, `status`, `manuallyDisabled`) and the lineup entries using them; `q` matches name, exact number or `tvg-id`, case-insensitively; `limit` 1–1000 (default 100) |
| GET | `/api/v1/admin/guides` | Guide import status: `lastSuccessAt`, `errorCode`, `channels`, retained `programmes` |
| GET | `/api/v1/admin/guides/{id}/channels?q=&limit=` | XMLTV channels of a guide (`channelId`, `displayName`, `icon`) |
| GET | `/api/v1/admin/lineup` | Every entry in lineup order |
| POST | `/api/v1/admin/lineup` | Create an entry; returns 201 |
| GET | `/api/v1/admin/lineup/{id}` | One entry |
| PATCH | `/api/v1/admin/lineup/{id}` | Update an entry |
| DELETE | `/api/v1/admin/lineup/{id}` | Remove an entry; returns 204 |
| PUT | `/api/v1/admin/lineup/order` | Reorder: `{"ids": [...]}` listing every entry once; returns the lineup |
| POST | `/api/v1/admin/lineup/adopt` | `{"providerId": "...", "enabled": true}` adds every channel of the provider whose sources no entry uses; returns `added` and `skipped` |
| POST | `/api/v1/admin/lineup/import` | Dispatcharr import, see below |
| GET, PUT | `/api/v1/admin/curation/settings` | Curation settings, see [curation](#curation) |
| GET, POST | `/api/v1/admin/curation/rules` | List rules; create one (201) |
| PUT, DELETE | `/api/v1/admin/curation/rules/{id}` | Replace or remove a rule (204) |
| POST | `/api/v1/admin/curation/import` | Create rules from the pipeline's data files |
| GET | `/api/v1/admin/curation/preview` | The plan a run would apply now, applying nothing |
| POST | `/api/v1/admin/curation/run` | Apply a plan now, whether or not automation is enabled; returns what was applied |
| GET | `/api/v1/admin/curation/changes?limit=` | Applied actions, newest first; `limit` 1–1000 (default 100) |

An entry has `id`, `position`, `number`, `name`, `group`, `logo`, `enabled`, `guide` (`{"guideId", "channelId"}` or null), `offerings` in failover order (`id`, `providerId`, `channelName`, `protocol`, `status`, `manuallyDisabled`; `status` is a health state or `WITHDRAWN` when no feed offers the source now), `guideStatus`, `pinned` (the fields the household set, which [curation](curation.md#pins) never changes), `createdBy` (`manual`, `import` or `rule:ID`) and, while curation rather than the household holds the entry disabled, `autoDisabledAt` and `autoDisabledReason` (`unavailable` or `alias`).

Create from a feed candidate, copying its number, name, logo, group, sources and (when its `tvg-id` is listed by a configured guide) guide mapping:

```sh
curl --fail http://127.0.0.1:8080/api/v1/admin/lineup \
  -H 'Content-Type: application/json' \
  --data '{"channelId": "FEED_CHANNEL_ID"}'
```

Any field in the body overrides the copied value and is pinned: `id` (a UUID), `number`, `name`, `group`, `logo`, `enabled` (true when absent, pinned only when sent), `offerings` (source IDs in failover order), `guide`, `position` (insertion index). Without `channelId`, `number` and `name` are required.

Update fields with `PATCH`; absent fields keep their value, `clearGroup`, `clearLogo` and `clearGuide` remove optional values, `position` moves the entry. Every field sent or cleared is pinned; `pin` and `unpin` lists change pins alone (`{"unpin": ["group"]}` lets curation manage the group again). Sending `enabled` either way makes the flag the household's: curation stops tracking a disable of its own.

```sh
curl --fail -X PATCH http://127.0.0.1:8080/api/v1/admin/lineup/ENTRY_ID \
  -H 'Content-Type: application/json' \
  --data '{"number": "501", "offerings": ["SOURCE_ID", "FALLBACK_SOURCE_ID"], "guide": {"guideId": "gracenote", "channelId": "XMLTV_ID"}}'
```

Validation: `number` 1–32 characters without spaces, `name` 1–256, `group` 1–128, `logo` an HTTP(S) URL, at most 64 sources per entry, every source ID known to a configured feed, `guideId` a configured guide. An active session keeps its current source until that source stops being selectable or the session ends.

### Dispatcharr import

`POST /api/v1/admin/lineup/import` takes up to 16 MiB:

```json
{
  "export": { "format": "sofa-dispatcharr-export/1", "profile": "sofaking", "epgSources": [...], "channels": [...] },
  "guideMap": { "4": "gracenote" },
  "dryRun": false,
  "includeUnmatched": false
}
```

`export` is the file `scripts/dispatcharr-export.py` writes. Each `epgSources` item has `id`, `name` and `url`; each channel has `uuid`, `number`, `name`, `group`, `logo`, `tvgId`, `epgSourceId`, `epgChannelId`, `enabled` and ordered `streams` (`url`, `name`, `provider`).

Streams match sources by [source key](live-tv.md#source-identity) (URL without the provider's renewable query parameters, tried under every provider's list), then, when exactly one source shares the stream's `scheme://authority/path`, by that path alone. An EPG source matches a configured guide by `scheme://authority/path` with the query ignored, then by a guide ID equal to its name, then by `guideMap` (`epgSourceId` to guide ID), which also overrides the automatic match. Entries take the channel `uuid` as their ID (a derived UUID when absent) and are created or updated in place. A channel with no matching stream is skipped unless `includeUnmatched` is true.

The report has `dryRun`, `created`, `updated`, `skipped`, `matchedStreams`, `unmatchedStreams`, `guideSources` (EPG source ID to guide ID or null) and `entries`, each with `number`, `name`, `uuid`, `action` (`created`, `updated`, `skipped`), `matchedStreams`, `unmatchedStreams` (source keys under the default renewable list), `guide` (`mapped`, `unmapped-source`, `none`) and, for a channel skipped because its number or name is invalid, `error`. `scripts/dispatcharr-import.py` posts a file and prints this report.

### Curation

The rules and settings that keep the lineup curated without Dispatcharr; what each one does is in [curation](curation.md). Every request takes the admin token.

`GET /api/v1/admin/curation/settings` returns the settings and `PUT` replaces them; absent fields take their defaults:

```json
{
  "enabled": false, "intervalSeconds": 900, "disableAfterHours": 24, "enableOn": "healthy",
  "equivalentEvidence": "name-and-guide", "numberFloor": 1000, "retiredLogoHosts": ["192.168.1.3:9192"], "defaultGroup": null
}
```

Validation: `intervalSeconds` 60–86400, `disableAfterHours` 1–720, `enableOn` `healthy` or `eligible`, `equivalentEvidence` `name-and-guide` or `name`, `numberFloor` 1–999999, each retired host `host` or `host:port` (lowercased), `defaultGroup` 1–128 characters. A change triggers a run.

A rule is `{"id", "kind", "position", "enabled", "match", "set", "note"}`; `POST` generates `id` when it is absent and answers `409 RULE_EXISTS` for a taken one, `PUT /rules/{id}` replaces the rule with that id (`404 RULE_NOT_FOUND`), and the list is in position order. `match` takes `providerId`, `group`, `groupPattern`, `name`, `namePattern`, `tvgId` and `entryId`; `set` takes `group`, `name`, `logo`, `enabled` and `canonicalEntryId`. Which fields a kind needs is in [rules](curation.md#rules); a rule that lacks them, names an unknown provider or entry, or carries a pattern that does not compile answers `400 INVALID_REQUEST`.

```sh
curl --fail http://127.0.0.1:8080/api/v1/admin/curation/rules \
  -H 'Content-Type: application/json' \
  --data '{"kind": "group", "match": {"group": "US: Sports"}, "set": {"group": "Sports"}}'
```

`GET /api/v1/admin/curation/preview` answers the plan: `generatedAt`, `enabled` (whether a run would apply it), `actions`, `counts` by kind and `findings`. An action has `kind` (`regroup`, `rename`, `relogo`, `clear-logo`, `attach`, `disable`, `enable`, `adopt`), `entryId`, `entryName`, `field`, `before`, `after`, `reason`, `ruleId` and, for `attach`, `offeringIds`. `POST /api/v1/admin/curation/run` applies a plan now and answers the same shape with `enabled` true and `actions` reduced to what was applied; an action whose entry changed under it (a field pinned since, a value no longer the one planned) is left out. `GET /api/v1/admin/curation/changes` lists applied actions as `{"id", "appliedAt", "action"}`, newest first; the server keeps the newest 10,000.

`POST /api/v1/admin/curation/import` takes up to 4 MiB with any of `groupMap`, `aliases`, `equivalents`, `sportsCorrections`, `corrections` and `dryRun` ([shapes](curation.md#import-the-pipelines-data)); fields the rules do not use are ignored. The report has `dryRun`, `created`, `updated`, `existing`, `skipped` and `items`, each `{"source", "item", "outcome", "ruleId", "reason"}` with `outcome` `created`, `updated`, `exists`, `skipped` or `applied-by-import`.

## Kotlin client

The `client` module provides `SofaBackendClient` and a playback handle. The client/contract target JVM 17 and use the Kotlin compiler version in [the version catalog](../gradle/libs.versions.toml).

Call these suspending methods from a coroutine. Supply the configured API token, or `null` for unauthenticated localhost access:

```kotlin
val client = SofaBackendClient("http://localhost:8080", token)
try {
    val channel = client.catalog().channels.first()
    val playback = client.openPlayback(channel.id, "preview")
    try {
        playback.promoteToWatching()
    } finally {
        playback.close()
    }
} finally {
    client.close()
}
```

During playback, collect `playback.state`. For `PlaybackState.Ready`, resolve the descriptor with `client.mediaUrl(descriptor)` and reopen media when `epoch` changes. `Unavailable` supplies an error code and retry flag; `Closed` ends playback.

Handles renew and fetch descriptors every 30 seconds. Close handles when their UI owner ends and close the client when its owner ends. Cleanup runs despite caller cancellation; DELETE has a 12-second deadline, with lease expiry covering unreachable servers.

`openPlayback` generates one idempotency key per call and retries a transport failure or server-side 5xx up to three times with that key inside its 12-second budget, so a retry resumes the session the first attempt created instead of opening another. Client-side errors such as `404 CHANNEL_NOT_FOUND` are not retried.

## Errors

Errors contain `code`, `message` and `retryable`:

```json
{
  "code": "SOURCE_UNAVAILABLE",
  "message": "No eligible source",
  "retryable": true
}
```

| Status | Common codes |
| --- | --- |
| 400 | `INVALID_REQUEST`, `INVALID_QUERY` |
| 401 | `AUTH_REQUIRED` |
| 404 | `CHANNEL_NOT_FOUND`, `SOURCE_NOT_FOUND`, `RESOURCE_NOT_FOUND`, `LINEUP_ENTRY_NOT_FOUND`, `GUIDE_NOT_FOUND`, `PROVIDER_NOT_FOUND`, `RULE_NOT_FOUND` |
| 409 | `RESYNC_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `LINEUP_ENTRY_EXISTS`, `RULE_EXISTS` |
| 410 | `SESSION_EXPIRED`, `STREAM_CHANGED` |
| 413 | `REQUEST_TOO_LARGE` |
| 429 | `CAPACITY_EXHAUSTED` |
| 502 | `UPSTREAM_UNAVAILABLE`, `UPSTREAM_REJECTED`, `INVALID_MANIFEST` |
| 503 | `NOT_READY`, `SOURCE_UNAVAILABLE` |
