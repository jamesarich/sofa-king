# Architecture

One Kotlin/JVM process serves the HTTP API and media relay. SQLite stores provider snapshots, detected source protocols, the lineup, guide channels and programs, server identity, source health, probe attempt times, observation history, the logo cache index, native provider state (device ids, tokens), and the curation rules, settings, availability state and change log; the logo files sit beside it in the data directory. Playback sessions and resolved native stream URLs exist in memory.

## Modules

| Module | Contents |
| --- | --- |
| `api-contract` | Serializable request/response models, including the admin, curation and lineup import models, JVM 17 |
| `client` | Ktor client and playback lease handles, JVM 17 |
| `server` | Ktor Netty server, imports, SQLite storage, lineup, source probes, media relay, player outputs and HDHomeRun emulation, JVM 21 |

## Data flow

```text
Provider M3U → FeedParser → SQLite snapshot → FeedService (candidates + offerings)
Service API → ProviderClient (providers/) → ProviderRegistry (svc:// offerings) → SQLite snapshot → FeedService
XMLTV (gzip ok) → FeedParser.guide (streaming) → SQLite guide tables → GuideService
Service guide API → ProviderClient.guide → SQLite guide tables → GuideService
svc:// offering → FeedService.playable → ProviderRegistry.resolve (cached until expiry) → MediaRelay / ProbeWorker / TsRemux
Admin API (including the lineup import) → LineupService → SQLite lineup tables
FeedService + GuideService + LineupService → CatalogService → JSON API
Lineup entry → SessionManager (ordered offerings by health) → MediaRelay → HLS/MPEG-TS player
Lineup + GuideService → /output M3U + XMLTV → third-party player → /output/stream → SessionManager (external session) → MediaRelay
Lineup → /discover.json + /lineup.json → Plex → /auto/v{number} → SessionManager (external session) → TsFanout, or TsRemux (FFmpeg stream copy) for HLS
Stream URL → ProbeWorker → HealthPolicy → SQLite source health
Lineup logo URLs → LogoCache (fetch, validate, data/logos/<key>) → /logos/{key} in the JSON API, M3U and XMLTV
Rules + settings (SQLite) + lineup + feeds + health → CurationService.plan → apply (one lineup transaction) → curation_change log
Policy file + feeds + guides + health + station_availability → StationBuilder.build → PolicyService.apply (one lineup transaction) → curation_change log, numbers written back to the file
```

Provider imports publish complete snapshots; invalid imports retain the previous snapshot. Guide imports replace one guide's rows atomically and keep only programs for channels the lineup maps onto that guide; the channel index is kept whole. Immutable in-memory indexes serve catalog requests; guide programs are read from SQLite by guide, channel and window. SQLite uses WAL, foreign keys, bounded blocking transactions and versioned atomic migrations whose versions may have gaps.

An offering's protocol is the one detected in its stream body, by the probe's HTTP tier or by a media fetch, stored per offering ID and applied over every published snapshot. Until a detection exists, the import's protocol (the entry's `stream-type`, the feed's `defaultProtocol`, then the path extension) applies.

## Native providers

`providers/` holds one `ProviderClient` per service behind `ProviderRegistry`, which `FeedService` and `GuideService` call for `type: native` providers. A client lists `ProviderChannel`s (the service's own ids, numbers, logos, group and region tags), optionally serves the service's guide as a `ParsedGuide` for the channel ids the lineup wants, and resolves a channel id to a `ResolvedStream` (URL, headers, expiry). The registry derives channel and offering ids the way `FeedParser` does, with `svc://service/id` as the offering URL, so identities are deterministic and the lineup, health and outputs see ordinary HLS offerings. `FeedService.playable(offering)` is the one seam: a native offering resolves through the registry (cached per offering, single-flight, dropped a minute before expiry, at most six hours), anything else returns its own URL and provider headers. `MediaRelay`, `ProbeWorker` and the HDHomeRun remux fetch what `playable` returns; the relay substitutes the resolved URL for the session's root resource only, since child playlists and segments are absolute by the time the player asks for them. A `ProviderFailure` is transient (provider evidence, `502` to a session) or not (the channel is gone: offering evidence). `ProviderContext` gives each client its options, HTTP through the upstream address policy with hand-followed redirects, gzip detection and the cookies a response sets, a short-lived shared cache for the service's payload, and durable state in `provider_state` (one random device id per provider per install, session tokens with expiry).

## Identity

The app's channel ID is the lineup entry UUID. Entries imported from a Dispatcharr export keep its channel UUID; others get a random UUID. Entries persist independently of feeds: a withdrawn source leaves the entry in place and is skipped for playback.

Feed channel IDs (candidates) use published `channel-uuid` values, otherwise a UUID derived from the provider ID and the exact `channel-id` or `tvg-id`, otherwise from the provider ID and the source key. The source key is the URL's lowercase scheme and authority, its percent-decoded path and its remaining query parameters sorted, after the provider's renewable parameters are dropped (`SourceIdentity.kt`; the default list and the `renewableQuery` override are in [live TV](live-tv.md#source-identity)). Source IDs use explicit `stream-id` values when supplied, otherwise derive from provider, channel key and source key, so a rotated token changes neither ID and two URLs that differ only by a renewable parameter are one source. The Dispatcharr import matches streams by the same source key, falling back to a lone `scheme://authority/path` match. See [migrate from Dispatcharr](migrate-from-dispatcharr.md).

Guide programs belong to a guide ID and XMLTV channel ID; an entry's `legacyGuideId` is its mapped XMLTV channel ID. Program IDs in the API derive from the entry ID, times and title.

## Lineup

`LineupService` keeps the entries in memory and writes the whole lineup in one transaction on each change, under a mutex. Each entry carries its ordered offering IDs and optional guide mapping. When a change maps channels a guide did not retain before, the guide's loop is asked to refresh; repeated requests before it runs coalesce. `CatalogService` resolves entries against current offerings, health and guide coverage; disabled entries are not served and are unknown to guide queries and playback.

## Lineup policy

With `SOFA_POLICY` set, `PolicyService` builds the lineup and `CurationService` is constructed but never started. `StationBuilder` is pure: it turns one snapshot of feed channels, guide listings, health and the remembered `station_availability` rows into stations, findings and the numbers it assigned. `apply` writes the stations through `LineupService.mutate` only when they differ, replaces the availability rows, logs field changes as `kind: "policy"` actions, then writes assigned numbers into the file. `PolicyFile` serializes every write behind one mutex, checks the on-disk SHA-256 against the loaded revision, and writes a temp file, fsyncs and renames. The loop runs on feed, guide and health triggers after a quiet period, and every 900 seconds. [Lineup policy](lineup-policy.md) describes the file and the run.

## Curation

`CurationService` holds the settings (one `metadata` row), the rules (`curation_rule`, patterns compiled once), the adopted-candidate set and the unavailable-since map in memory, each loaded from SQLite at start. A run takes a snapshot (lineup, health states, settings, rules), computes a plan of steps in a fixed order (availability, alias merges, equivalent attachment, adoption, display fields) plus findings, writes the availability rows that changed, and, when enabled or forced, applies the steps inside one `LineupService.mutate`, re-checking each against the entry as it then stands (field unpinned, old value unchanged, cap not exceeded) before recording it in `curation_change` (newest 10,000 kept). Imports and health changes signal a conflated trigger; the loop waits a quiet period, drains it and runs, and otherwise runs every `intervalSeconds`. `preview` is the same plan with nothing written. Entry columns `pinned`, `created_by`, `auto_disabled_at` and `auto_disabled_reason` (V040) carry an admin's pins and automation's own disables; `curation_entry_state` holds unavailable-since separately so bookkeeping never rewrites the lineup. [Curation](curation.md) lists the rules and the pass.

## Playback

Sessions select from the entry's offerings: healthy before unverified before suspect, lineup order within a rank. They have preview/watch intent (45-second and 90-second leases), idempotency keys and stream epochs. At capacity the oldest preview session is evicted. Release, expiry, eviction and source disable cancel active media requests.

The HLS relay rewrites variant, audio, subtitle, key, initialization-map and segment URLs into opaque session paths. It preserves playlist tags and byte ranges, and forwards the LL-HLS delivery directives `_HLS_msn`, `_HLS_part` and `_HLS_skip` from a playlist request to the upstream playlist. Token lookups are O(1) in both directions. Root and playlist tokens are pinned for the session's epoch; segments and keys share a 4,096-entry LRU. Limits are 12 active requests per session and 32 concurrent upstream connections across the server. Upstream addresses follow the [address policy](live-tv.md#upstream-addresses); a refused address answers `502 UPSTREAM_REJECTED`.

## Player outputs

`/output/playlist.m3u` and `/output/guide.xml` are rendered from the catalog and the stored programs on each request; nothing is cached or stored for them. `/output/stream/{id}` opens an external session: a watch session with no idempotency key whose lease is the player's activity. `SessionManager.acquire` and `finished` move its expiry 60 seconds past each media request, a request still in flight exempts it from reaping, and the request handler releases it when a TS response ends. Capacity eviction prefers a preview, then an external session with no request in flight. An HLS source is answered with a redirect to the session's root playlist, so one session serves every player on that channel; an MPEG-TS source is served through the same fan-out as an API session. The handler catches the relay's protocol-mismatch `410` and switches paths within the request.

## Logos

`LogoCache` keys each lineup logo by the SHA-256 of its source URL and keeps an in-memory index of the `logo_cache` rows, which name the stored content type, size, content hash, last fetch and last failure. Files live at `data/logos/<key>`, written to a temporary name and moved into place. A fetch goes through `withUpstreamResponse` with the feed address policy and no provider headers, requires an image content type, caps the body at 2 MiB (`Content-Length` first, then the read), and types the body by its signature, so a mislabeled PNG is stored as PNG and a declared SVG must be markup; anything else is a failure row. Fetches are single-flight per key and share a semaphore of four. `FeedService` and `LineupService` signal a conflated trigger after each import or change; the sweep loop in the runtime scope then fetches every wanted key that is missing, or a week old and not failed within the hour, and deletes rows and files the lineup no longer names. A request for a key with no usable file fetches it inline when the lineup names it. The catalog and output routes rewrite a channel's logo to `BASE/logos/<key>` from the request `Host`, so the channels ETag covers the base as well as the catalog revision.

## HDHomeRun emulation

`HdhrRoutes` renders the discovery, lineup and status documents from the lineup on each request; the device ID is configured or derived from the server identity, so it survives restarts. `/auto/v{number}` opens an external session per request, never shared, counted against `SOFA_HDHR_TUNERS` separately from the session cap. An MPEG-TS source reuses `MediaRelay`. An HLS source goes to `TsRemux`, which runs one FFmpeg per response reading the source URL directly (the address policy is checked first, provider headers only when portable) with `-c copy` into `pipe:1`, and copies the pipe into the response. The request's job is attached to the session so it counts as in flight; a watchdog coroutine kills the process when the response is cancelled, because a blocked pipe read does not notice cancellation, and the process is always destroyed and waited for before the handler returns. `LiveRuntime.close` kills every remux. The allow-list (`AddressAllowList`) matches IP literals and CIDR blocks against the connection's remote address; IPv4-mapped IPv6 addresses are compared as IPv4.

## Live MPEG-TS relay

Sessions playing the same MPEG-TS source share one upstream connection. The relay forwards whole 188-byte packets unchanged, so captions and other in-band data pass through. Each session reads through its own bounded buffer of 128 chunks; a session that falls behind is disconnected while the upstream and the other sessions continue. A new media request from a session replaces its previous MPEG-TS request.

When the upstream ends or fails, the relay reconnects with backoff from 0.5 to 8 seconds for a 30-second grace period measured from the last received packet. Throughout any gap of more than a second it writes MPEG-TS null packets (PID 0x1FFF) to every session about once per second, so an 8-second player read timeout never fires. Sessions end only when the grace period expires. The upstream socket has a 15-second inactivity timeout, which counts as a failure and starts the grace period. An upstream with no sessions closes after two seconds.

## Supported scope

Configured HTTP(S) feeds and guides, JSON control and admin requests, HLS/MPEG-TS passthrough, M3U/XMLTV output with per-channel stream URLs for third-party players, cached lineup logos served from this server, HDHomeRun emulation with an HLS-to-MPEG-TS stream-copy remux for Plex, and FFmpeg source qualification are supported. Recordings, timeshift, transcoding, shared HLS upstreams, SSDP discovery, web administration, device pairing and events are not implemented. HLS variable substitution and content steering are unsupported.

## Operations

Health observation history is pruned to `SOFA_OBSERVATION_RETENTION_DAYS` (default 7) at the start of each probe batch; source health, strikes and probe attempt times are separate rows and are never pruned. The channels ETag derives from the lineup, the published channels and offerings, each guide's import time, the health table's row count and latest update, which entries' guides are still current, and the request's `Host` (the logo URLs carry it), so a matching `If-None-Match` is answered without building the body.

Logs are key=value lines: provider imports (counts, skipped entries, duration, failure class), logo fetch failures (key, exception class, the message with every URL replaced by `<url>`, whether a stale file remains), guide imports (channels, retained programs, duration; on failure the exception class and its message with every URL replaced by `<url>`), probe batches (outcome counts, duration) and health transitions (source ID, old and new state), curation runs (planned, applied and finding counts, whether enabled, duration) and each applied action (kind, entry ID, field, rule ID; never the value, which can be a logo URL), relay upstream reconnects and failures (source ID, attempt, exception class, HTTP status). A request that fails with a closed channel, broken pipe or connection reset, as when a player switches channels mid-body, logs at debug with the exception class; other request failures log at error. Stream URLs, guide URLs, query tokens and provider exception messages are never logged; a guide failure message is logged only after URL redaction.

On SIGTERM the shutdown hook stops the HTTP server, closes the runtime (imports, probes, relay upstreams and sessions) and then the store, in that order, and logs each step.

[API usage](api.md) and [source-health behavior](source-health.md) describe the server interface.
