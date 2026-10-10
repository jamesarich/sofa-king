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

Request bodies are limited to 64 KiB, except the lineup import (16 MiB). The [contract module](../api-contract/src/main/kotlin/dev/jamesrich/sofaking/backend/api/) defines the response fields.

Channel IDs are lineup entry IDs. A channel's `displayNumber`, `name`, `tags` (the group) and `logo` come from its entry; `legacyGuideId` is the mapped XMLTV channel ID; `guideStatus` is `unassigned`, `empty`, `available` or `stale`; `playbackEligible` is true when any of the entry's sources is selectable. Channels are listed in lineup order.

Each provider status reports `lastSuccessAt`, `errorCode` (`IMPORT_FAILED` when the last import kept the previous snapshot), `channels` and `sources` from the last successful import, `skippedEntries` and `skipReasons`. Skip reasons are counts by short key, for example `duplicate-source=1, missing-id=3`: `missing-id`, `missing-name`, `invalid-url`, `invalid-uuid`, `duplicate-key` (one identity with a different name or number), `duplicate-source` (the same source with only a different query string and no `stream-id`), `bad-protocol` and `no-extinf`.

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

An entry has `id`, `position`, `number`, `name`, `group`, `logo`, `enabled`, `guide` (`{"guideId", "channelId"}` or null), `offerings` in failover order (`id`, `providerId`, `channelName`, `protocol`, `status`, `manuallyDisabled`; `status` is a health state or `WITHDRAWN` when no feed offers the source now) and `guideStatus`.

Create from a feed candidate, copying its number, name, logo, group, sources and (when its `tvg-id` is listed by a configured guide) guide mapping:

```sh
curl --fail http://127.0.0.1:8080/api/v1/admin/lineup \
  -H 'Content-Type: application/json' \
  --data '{"channelId": "FEED_CHANNEL_ID"}'
```

Any field in the body overrides the copied value: `id` (a UUID), `number`, `name`, `group`, `logo`, `enabled`, `offerings` (source IDs in failover order), `guide`, `position` (insertion index). Without `channelId`, `number` and `name` are required.

Update fields with `PATCH`; absent fields keep their value, `clearGroup`, `clearLogo` and `clearGuide` remove optional values, `position` moves the entry:

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

Streams match sources by `scheme://authority/path` with the query string ignored. An EPG source matches a configured guide by that same key, then by a guide ID equal to its name, then by `guideMap` (`epgSourceId` to guide ID), which also overrides the automatic match. Entries take the channel `uuid` as their ID (a derived UUID when absent) and are created or updated in place. A channel with no matching stream is skipped unless `includeUnmatched` is true.

The report has `dryRun`, `created`, `updated`, `skipped`, `matchedStreams`, `unmatchedStreams`, `guideSources` (EPG source ID to guide ID or null) and `entries`, each with `number`, `name`, `uuid`, `action` (`created`, `updated`, `skipped`), `matchedStreams`, `unmatchedStreams` (keys without query strings) and `guide` (`mapped`, `unmapped-source`, `none`). `scripts/dispatcharr-import.py` posts a file and prints this report.

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
| 404 | `CHANNEL_NOT_FOUND`, `SOURCE_NOT_FOUND`, `RESOURCE_NOT_FOUND`, `LINEUP_ENTRY_NOT_FOUND`, `GUIDE_NOT_FOUND`, `PROVIDER_NOT_FOUND` |
| 409 | `RESYNC_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `LINEUP_ENTRY_EXISTS` |
| 410 | `SESSION_EXPIRED`, `STREAM_CHANGED` |
| 413 | `REQUEST_TOO_LARGE` |
| 429 | `CAPACITY_EXHAUSTED` |
| 502 | `UPSTREAM_UNAVAILABLE`, `UPSTREAM_REJECTED`, `INVALID_MANIFEST` |
| 503 | `NOT_READY`, `SOURCE_UNAVAILABLE` |
