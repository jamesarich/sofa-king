# Architecture

One Kotlin/JVM process serves the HTTP API and media relay. SQLite stores catalog snapshots, server identity, source health, probe attempt times, observation history and detected source protocols. Playback sessions exist in memory.

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

An offering's protocol is the one detected in its stream body, by the probe's HTTP tier or by a media fetch, stored per offering ID and applied over every published snapshot. Until a detection exists, the import's protocol (the entry's `stream-type`, the feed's `defaultProtocol`, then the path extension) applies.

## Identity

Published `channel-uuid` values are preserved. Otherwise a channel UUID derives from its provider ID and exact `channel-id` or `tvg-id`. XMLTV matches `tvg-id`; channel names do not merge stations.

Source IDs use explicit `stream-id` values when supplied. Otherwise they derive from provider/channel and URL scheme, authority and path, excluding renewable query tokens. Query-only alternatives require distinct `stream-id` values. Use stable explicit IDs when provider URL paths change.

Withdrawn sources lose playback eligibility. Their channels and health records remain stored. `legacyGuideId` carries the original guide key.

## Playback

Sessions select healthy sources before unverified ones, then suspect ones. They have preview/watch intent (45-second and 90-second leases), idempotency keys and stream epochs. At capacity the oldest preview session is evicted. Release, expiry, eviction and source disable cancel active media requests.

The HLS relay rewrites variant, audio, subtitle, key, initialization-map and segment URLs into opaque session paths. It preserves playlist tags and byte ranges, and forwards the LL-HLS delivery directives `_HLS_msn`, `_HLS_part` and `_HLS_skip` from a playlist request to the upstream playlist. Token lookups are O(1) in both directions. Root and playlist tokens are pinned for the session's epoch; segments and keys share a 4,096-entry LRU. Limits are 12 active requests per session and 32 concurrent upstream connections across the server. Upstream addresses follow the [address policy](live-tv.md#upstream-addresses); a refused address answers `502 UPSTREAM_REJECTED`.

## Live MPEG-TS relay

Sessions playing the same MPEG-TS source share one upstream connection. The relay forwards whole 188-byte packets unchanged, so captions and other in-band data pass through. Each session reads through its own bounded buffer of 128 chunks; a session that falls behind is disconnected while the upstream and the other sessions continue. A new media request from a session replaces its previous MPEG-TS request.

When the upstream ends or fails, the relay reconnects with backoff from 0.5 to 8 seconds for a 30-second grace period measured from the last received packet. Throughout any gap of more than a second it writes MPEG-TS null packets (PID 0x1FFF) to every session about once per second, so an 8-second player read timeout never fires. Sessions end only when the grace period expires. The upstream socket has a 15-second inactivity timeout, which counts as a failure and starts the grace period. An upstream with no sessions closes after two seconds.

## Supported scope

Configured HTTP(S) feeds, JSON control requests, HLS/MPEG-TS passthrough and FFmpeg source qualification are supported. Recordings, timeshift, transcoding/remuxing, shared HLS upstreams, web administration, device pairing and events are not implemented. HLS variable substitution and content steering are unsupported.

## Operations

Health observation history is pruned to `SOFA_OBSERVATION_RETENTION_DAYS` (default 7) at the start of each probe batch; source health, strikes and probe attempt times are separate rows and are never pruned. The channels ETag derives from the published channels and offerings, the guide coverage, the health table's row count and latest update, and which channels' guides are still current, so a matching `If-None-Match` is answered without building the body.

Logs are key=value lines: imports (counts, skipped entries, duration, failure class), probe batches (outcome counts, duration) and health transitions (source ID, old and new state), relay upstream reconnects and failures (source ID, attempt, exception class, HTTP status). A request that fails with a closed channel, broken pipe or connection reset, as when a player switches channels mid-body, logs at debug with the exception class; other request failures log at error. Stream URLs, query tokens and provider exception messages are never logged.

On SIGTERM the shutdown hook stops the HTTP server, closes the runtime (imports, probes, relay upstreams and sessions) and then the store, in that order, and logs each step.

The Android app currently uses its separate M3U/XMLTV adapter. [API usage](api.md) and [source-health behavior](source-health.md) describe the server interface.
