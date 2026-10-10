# Live TV implementation

The backend now imports configured M3U/XMLTV feeds, serves a native catalog and bounded guide, creates leased playback sessions, relays HLS/TS and runs persisted decode qualification/recovery. The bridge adapter makes the current household lineup usable without first porting every provider. It is a migration adapter; the native client consumes JSON and owned playback descriptors.

## Configure and run

Use JDK 21 and FFmpeg on PATH. `SOFA_FFMPEG` overrides the executable path. Source configuration is a local JSON file; store it under the ignored `data/` directory because URLs and headers can contain credentials.

Create `data/sources.json` using the JSON example below before starting the server.

```sh
mkdir -p data
./gradlew test :server:installDist
SOFA_SOURCES=./data/sources.json server/build/install/server/bin/server
```

On James's shared host, build with `/home/james/.claude/bin/gradle-queue -- test :server:installDist`.

```json
[
  {
    "id": "household-bridge",
    "playlistUrl": "http://192.168.1.3:9192/sofaking/playlist.m3u",
    "guideUrl": "http://192.168.1.3:9192/sofaking/epg.xml",
    "refreshSeconds": 900,
    "probe": false
  }
]
```

The household bridge example disables new probes while the old stack still owns qualification. To qualify sources through the new worker, set `probe: true` (the default). Optional `defaultProtocol` (`hls` or `mpegts`) supports extensionless provider URLs; per-entry `stream-type` takes precedence. Optional `headers` is a string map for provider requests. `allowEmpty: true` explicitly permits an empty playlist to withdraw a provider's offerings. Empty or invalid imports otherwise preserve the last complete snapshot.

`SOFA_HOST` defaults to `127.0.0.1`, `SOFA_PORT` to `8080`, and `SOFA_DATA_DIR` to `data`. A non-loopback bind requires `SOFA_API_TOKEN` with at least 32 characters. API requests then require `Authorization: Bearer TOKEN`. `SOFA_MAX_SESSIONS` defaults to four and permits 1–16. Use a TLS reverse proxy when carrying the token beyond a trusted local connection. This version has one household/admin token; device pairing and separate administrative roles are follow-on work.

## Native API

| Method and path | Behavior |
| --- | --- |
| `GET /healthz`, `GET /readyz` | Public liveness and storage readiness |
| `GET /api/v1/bootstrap` | Persistent server identity, UTC time and implemented capabilities |
| `GET /api/v1/channels` | Stable channel UUID, display number, name/artwork/tags, legacy guide key, guide status and eligibility; ETag/If-None-Match |
| `POST /api/v1/guide/query` | 1–200 distinct channel UUIDs, positive window at most 24 hours, optional pinned revision |
| `GET /api/v1/providers` | Last import success, safe error code and channel count |
| `GET /api/v1/sources` | Offering health, manual-disable flag and recovery due time; no upstream URLs |
| `PATCH /api/v1/sources/{id}` | Set `manuallyDisabled` without resetting quarantine or deleting identity |
| `POST /api/v1/playback-sessions` | Channel UUID, `preview`/`watch`, unique idempotency key; returns descriptor |
| `GET /api/v1/playback-sessions/{id}` | Current descriptor; changed/withdrawn/ineligible offerings cause source reselection with a new epoch |
| `PATCH /api/v1/playback-sessions/{id}` | Renew 90-second lease; optional intent promotion preserves the stream |
| `DELETE /api/v1/playback-sessions/{id}` | Idempotent release; cancels current upstream requests |
| `GET /media/sessions/{id}/{epoch}/{resource}` | Session-scoped opaque resource URL; possession authorizes media reads until release/expiry |

Replace `CHANNEL_UUID` with a catalog ID and choose a UTC window no longer than 24 hours:

```json
{
  "channelIds": ["CHANNEL_UUID"],
  "from": "2026-10-10T13:00:00Z",
  "to": "2026-10-10T19:00:00Z"
}
```

Programme overlap uses half-open intervals: `startsAt < to && endsAt > from`. All times are UTC instants. XMLTV offsets are preserved; missing/invalid offsets and nonpositive intervals are discarded, rather than assigned an assumed household timezone. A mismatched guide revision returns `409 RESYNC_REQUIRED`. Control request bodies are capped at 64 KiB; imports cap playlists at 16 MiB and XMLTV at 64 MiB, 10,000 channels, 20,000 offerings and 250,000 programmes.

## Client ergonomics

The `client` JVM module supplies `SofaBackendClient`: `bootstrap`, `catalog`, `guide`, `openPlayback` and an owned playback handle with `StateFlow<PlaybackState>`, `promoteToWatching` and suspending `close`. It renews leases and checks descriptors every 30 seconds. If the epoch changes, the consuming player must reopen the new descriptor. Creation is serialized against client shutdown. Cleanup runs even when the caller is canceled, with a 12-second release deadline. UI cancellation after a late creation response releases the session; unreachable cleanup is bounded by the server lease.

Observe `playback.state`; resolve ready descriptors with `client.mediaUrl(descriptor)` and reopen the player when the epoch changes. Close the handle when leaving playback and the client when its owner ends.

```kotlin
val client = SofaBackendClient("http://backend:8080", token)
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

The shared contract/client compile to JVM 17 using the JDK 21 toolchain; the server targets JVM 21. They use the preview Kotlin compiler and metadata, so the consuming Sofa King build must adopt a compatible Kotlin version before integration. The Android app has compatible preview compiler pins but has not integrated these routes. Its favorite/last-channel migration should join existing numeric keys through `legacyGuideId`, then persist UUIDs.

## Identity, imports and storage

Published `channel-uuid` values are preserved. Otherwise channel UUIDs derive from the configured provider ID plus exact `channel-id`/`tvg-id`. Station names never drive fuzzy merges. XMLTV joins use `tvg-id`. Changing provider IDs changes fallback identities, so keep IDs stable.

Offering identities prefer explicit `stream-id`; otherwise they derive from provider/channel plus URL scheme/authority/path, excluding renewable query tokens. Alternative ordering does not reset health. Query-only alternatives require explicit distinct `stream-id` values; rotating URL paths should also supply a stable `stream-id`. Failed/ambiguous imports preserve the last good snapshot. Source credentials remain server-side.

Each provider's validated catalog/guide snapshot is persisted atomically as an internal JSON document in SQLite metadata, with normalized offering-health and incident tables. This first adapter favors simple atomic activation and restart recovery. Immutable in-memory indexes and a precomputed guide revision keep bounded queries from repeatedly hashing or scanning the full national guide. It retains one guide revision; old pinned revisions return resync. It does not yet stage multiple large revisions or index programme rows for multi-household scale. Removed offerings lose eligibility; their channels remain in the catalog with playback unavailable, preserving UUIDs, display metadata and favorite references. Health is retained; a later rediscovery restores the same IDs and overrides. Household catalog edits and custom guide mappings still originate in the existing export until their native management slice lands.

## Media and source health

The relay rewrites HLS variant, audio/subtitle, key, map and segment URIs into opaque session paths, preserving rendition/timing/discontinuity tags. It resolves relative URLs against the final upstream response URL and forwards byte ranges. Safe filename extensions survive for player compatibility. Each session bounds active requests and its resource map; a shared semaphore bounds media HTTP work. Long TS responses have a 15-second inactivity timeout and end when the request, session or server is cancelled.

Provider headers are scoped to the configured upstream origin. Cross-origin CDN requests receive only User-Agent, Accept, Accept-Language, Origin and Referer headers; Authorization, Cookie and custom secret headers are stripped. Redirects are limited to five, and HTTPS-to-HTTP media or feed transitions are rejected. Providers that require credentials on a different CDN origin need an explicit per-origin credential design before they can play.

HLS variable substitution/content steering and non-HTTP resource schemes are rejected explicitly. There is no transcode, TS-to-HLS remux, shared upstream fanout, persisted segment cache or timeshift yet. A `.m3u8` URL implies HLS; extensionless streams currently default to a TS descriptor even when the relay detects a manifest by response content type. Set the feed's `defaultProtocol` or per-entry `stream-type` for such providers. Media3/caption behavior still needs actual Android/Chromecast acceptance; server-side FFmpeg decoding does not certify it.

Direct FFmpeg probes skip feeds whose headers include credentials or custom headers, because FFmpeg cannot enforce the relay's per-origin header policy. Playback still works through the credential-scoped relay. Qualification through the relay is required before enabling probes for those feeds.

One bounded worker qualifies configured sources with 30 seconds of decoded video, at least 30 frames and at least 29 seconds of media timestamps, with a 50-second process deadline. Persistent probe-attempt times prevent retries after restart from bypassing minimum spacing. Healthy offerings recheck after six hours; suspect/unverified offerings retry no sooner than five minutes. The worker chooses the oldest attempted eligible offering, so a dead provider cannot monopolize the queue. The four-session budget excludes the single background probe; provider-specific concurrency reservations are follow-on work.

Three independent offering failures within six hours spanning ten minutes quarantine the offering. Initial recovery is due after 15 minutes; failed recovery backs off to one hour, six hours and daily. A passing recovery starts probation, then another passing qualification at least ten minutes later restores eligibility. New sessions exclude quarantine/probation/manual disables; channel identity remains intact. Provider HTTP 5xx/network faults, auth/rate limits and recognized local decoder/resource faults do not become offering strikes. Classification is conservative but heuristic; a full provider-wide correlation/circuit breaker is still planned. Probe decoding currently qualifies the upstream source; relay decoding is verified separately by acceptance scripts.

Automatic archival, withdrawn-source retention rules and incident-ledger pruning remain explicit follow-on work. The current ledger provides exact deduplication and grows with observations. The worker persists scheduling timestamps; it is not a general durable job queue.

## Verification

```sh
python3 scripts/smoke.py
python3 scripts/live-smoke.py
python3 scripts/bridge-smoke.py \
  --playlist http://192.168.1.3:9192/sofaking/playlist.m3u \
  --guide http://192.168.1.3:9192/sofaking/epg.xml \
  --decode-channel 'WOI ABC 5 Des Moines'
```

The live smoke uses generated H.264/AAC fixture media, real HTTP and the packaged server. It checks import, bounded guide, preview promotion, four concurrent HLS video decoders through the relay, automatic qualification, active TS upstream cancellation, release and restart. The bridge smoke starts a disposable backend with probes disabled; its optional single-channel decode is a normal read-only stream request against the existing provider path.

The 2026-10-10 household bridge run imported 916 channels, returned 269 real programmes for 50 guide rows and decoded WOI ABC 5 Des Moines through the new server. Counts describe that run, not a promise about future upstream availability. No production writes or cutover occurred.
