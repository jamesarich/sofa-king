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

The HLS relay rewrites variant, audio, subtitle, key, initialization-map and segment URLs into opaque session paths. It preserves playlist tags and byte ranges. Limits are 12 active requests and 4,096 mapped resources per session, and 32 concurrent upstream connections across the server.

## Live MPEG-TS relay

Sessions playing the same MPEG-TS source share one upstream connection. The relay forwards whole 188-byte packets unchanged, so captions and other in-band data pass through. Each session reads through its own bounded buffer of 128 chunks; a session that falls behind is disconnected while the upstream and the other sessions continue. A new media request from a session replaces its previous MPEG-TS request.

When the upstream ends or fails, the relay reconnects with backoff from 0.5 to 8 seconds for a 30-second grace period measured from the last received packet. Throughout any gap of more than a second it writes MPEG-TS null packets (PID 0x1FFF) to every session about once per second, so an 8-second player read timeout never fires. Sessions end only when the grace period expires. The upstream socket has a 15-second inactivity timeout, which counts as a failure and starts the grace period. An upstream with no sessions closes after two seconds.

## Supported scope

Configured HTTP(S) feeds, JSON control requests, HLS/MPEG-TS passthrough and FFmpeg source qualification are supported. Recordings, timeshift, transcoding/remuxing, shared HLS upstreams, web administration, device pairing and events are not implemented. HLS variable substitution and content steering are unsupported. Observation history has no automatic pruning.

The Android app currently uses its separate M3U/XMLTV adapter. [API usage](api.md) and [source-health behavior](source-health.md) describe the server interface.
