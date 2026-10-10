# Architecture

One Kotlin/JVM process serves the HTTP API and media relay. SQLite stores catalog snapshots, server identity, source health, probe attempt times and observation history. Playback sessions exist in memory.

## Modules

| Module | Contents |
| --- | --- |
| `api-contract` | Serializable request/response models, JVM 17 |
| `client` | Ktor client and playback lease handles, JVM 17 |
| `server` | Ktor Netty server, imports, SQLite storage, source probes and media relay, JVM 21 |

## Data flow

```text
M3U + XMLTV → FeedParser → SQLite snapshot → FeedService → JSON API
Stream URL → SessionManager → MediaRelay → HLS/MPEG-TS player
Stream URL → ProbeWorker → HealthPolicy → SQLite source health
```

Imports publish complete snapshots. Invalid imports retain the previous snapshot. Immutable in-memory catalog/guide indexes serve requests. SQLite uses WAL, foreign keys, bounded blocking transactions and versioned atomic migrations.

## Identity

Published `channel-uuid` values are preserved. Otherwise a channel UUID derives from its provider ID and exact `channel-id` or `tvg-id`. XMLTV matches `tvg-id`; channel names do not merge stations.

Source IDs use explicit `stream-id` values when supplied. Otherwise they derive from provider/channel and URL scheme, authority and path, excluding renewable query tokens. Query-only alternatives require distinct `stream-id` values. Use stable explicit IDs when provider URL paths change.

Withdrawn sources lose playback eligibility. Their channels and health records remain stored. `legacyGuideId` carries the original guide key.

## Playback

Sessions select healthy sources before other eligible sources. They have 90-second leases, preview/watch intent, idempotency keys and stream epochs. Release, expiry and source disable cancel active media requests.

The HLS relay rewrites variant, audio, subtitle, key, initialization-map and segment URLs into opaque session paths. It preserves playlist tags and byte ranges. Limits are 12 active requests and 4,096 mapped resources per session, and 32 concurrent media requests across the server. MPEG-TS has a 15-second socket inactivity timeout and no total stream-duration timeout.

## Supported scope

Configured HTTP(S) feeds, JSON control requests, HLS/MPEG-TS passthrough and FFmpeg source qualification are supported. Recordings, timeshift, transcoding/remuxing, shared upstream streams, web administration, device pairing and events are not implemented. HLS variable substitution and content steering are unsupported. Observation history has no automatic pruning.

The Android app currently uses its separate M3U/XMLTV adapter. [API usage](api.md) and [source-health behavior](source-health.md) describe the server interface.
