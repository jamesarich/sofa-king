# API

The API uses JSON under `/api/v1`. All timestamps are UTC instants. When `SOFA_API_TOKEN` is configured, send `Authorization: Bearer TOKEN` on API requests.

## Endpoints

| Method | Path | Response or action |
| --- | --- | --- |
| GET | `/healthz` | Public liveness check |
| GET | `/readyz` | Public database readiness check |
| GET | `/api/v1/bootstrap` | Server ID, time, API version and capabilities |
| GET | `/api/v1/channels` | Catalog revision and channel list; supports ETag/If-None-Match |
| GET | `/api/v1/providers` | Provider import status and channel counts |
| GET | `/api/v1/sources` | Source health and manual-disable flags |
| PATCH | `/api/v1/sources/{id}` | Set `manuallyDisabled` |
| POST | `/api/v1/guide/query` | Programs overlapping a requested channel/time window |
| POST | `/api/v1/playback-sessions` | Create a preview or watch session |
| GET | `/api/v1/playback-sessions/{id}` | Read the current playback descriptor |
| PATCH | `/api/v1/playback-sessions/{id}` | Renew the lease and optionally change intent |
| DELETE | `/api/v1/playback-sessions/{id}` | Release the session; returns 204 |
| GET | `/media/sessions/{id}/{epoch}/{resource}` | Session-authorized HLS or MPEG-TS media |

Request bodies are limited to 64 KiB. The [contract module](../api-contract/src/main/kotlin/dev/jamesrich/sofaking/backend/api/) defines the response fields.

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

Request 1–200 distinct channels and a positive window of at most 24 hours. Programs overlap when `startsAt < to && endsAt > from`.

To page through a consistent guide, pass the returned `revision` in subsequent requests. A changed revision returns `409 RESYNC_REQUIRED`; query again without the old revision.

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

The response contains `id`, `expiresAt`, and a `descriptor` with `protocol`, `mimeType`, relative `url`, and `epoch`. Resolve the URL against the server address and give it to the player.

The descriptor's `protocol`, `mimeType` and URL suffix (`.m3u8` or `.ts`) come from the source's protocol, not from the upstream path. If the first media fetch finds the other protocol (an M3U behind an extensionless URL, or MPEG-TS behind a `.m3u8` path), the server records it, increments the epoch and answers `410 STREAM_CHANGED`; fetch the session and open the new URL. Later sessions on that source start with the detected protocol.

Sessions expire after 90 seconds without renewal. Renew and promote a preview without replacing its stream:

```sh
curl --fail -X PATCH \
  http://127.0.0.1:8080/api/v1/playback-sessions/SESSION_ID \
  -H 'Content-Type: application/json' \
  --data '{"intent":"watch"}'
```

Send `{}` to renew without changing intent. Fetch the session periodically; if its descriptor epoch changes, reopen the returned media URL.

Release the session when playback ends:

```sh
curl --fail -X DELETE \
  http://127.0.0.1:8080/api/v1/playback-sessions/SESSION_ID
```

Release is idempotent and cancels active upstream requests. Repeating an open request with the same key returns the same session while it remains active. A reused key with different parameters returns `409 IDEMPOTENCY_CONFLICT`.

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
| 404 | `CHANNEL_NOT_FOUND`, `SOURCE_NOT_FOUND`, `RESOURCE_NOT_FOUND` |
| 409 | `RESYNC_REQUIRED`, `IDEMPOTENCY_CONFLICT` |
| 410 | `SESSION_EXPIRED`, `STREAM_CHANGED` |
| 413 | `REQUEST_TOO_LARGE` |
| 429 | `CAPACITY_EXHAUSTED` |
| 502 | `UPSTREAM_UNAVAILABLE`, `INVALID_MANIFEST` |
| 503 | `NOT_READY`, `SOURCE_UNAVAILABLE` |
