# Working live TV milestone

James authorized continuing autonomously with the lightweight process until a working implementation or a required user decision. This milestone builds on the verified foundation; the entire replacement roadmap remains in [architecture](../architecture.md).

## Implemented

- [x] Configured bridge ingestion, validated stable identities, atomic last-good snapshots and persistent channel history when offerings disappear.
- [x] Native catalog/ETags, source/provider status and half-open bounded guide queries with explicit revision resync.
- [x] Authenticated API for non-loopback binds; bounded control bodies and source manual-disable endpoint.
- [x] Bounded idempotent preview/watch sessions, promotion, leases, release/cancellation and explicit stream epochs.
- [x] Streaming TS and HLS relay, including variant/audio/subtitle/key/map rewriting, byte ranges and safe media extensions.
- [x] Automatic decode qualification, persisted retry scheduling, quarantine/probation/backoff and live-session invalidation on disable/quarantine.
- [x] Typed Kotlin client handles, automatic lease renewal and late-response cleanup after UI cancellation; Android-compatible JVM 17 bytecode with preview Kotlin metadata.
- [x] Real SQLite, native API, parser/security, worker and client-cancellation behavior tests; installed-distribution acceptance scripts.
- [x] Read-only household feed acceptance: 916 channels, 50 guide rows/269 real programmes and WOI ABC 5 Des Moines video decoded through the new backend.

## Verification and decisions

The initial implementation passed 53 tests and the read-only household bridge check on October 10. The subsequent [code and documentation review](../review-2026-10-10.md) passed 61 tests and both installed-server smoke checks, including four concurrent HLS decoders and active TS upstream cancellation. Acceptance commands and current implementation limits are in [live TV documentation](../live-tv.md).

The packaged HLS decode check discovered that opaque URLs without media extensions fail FFmpeg's segment extension checks. Resource URLs now retain safe extensions while hiding origin URLs. Offering identities exclude renewable URL query tokens and survive alternative reordering; ambiguous query-only alternatives require explicit `stream-id`. The source worker spaces independent strikes by at least five minutes and persists attempts before probing, so restart cannot accelerate strikes. Guide indexes/revisions are precomputed at snapshot publication instead of scanning and hashing the entire guide for each bounded query.

The initial backend milestone changed no production state or Android files. The subsequent cross-repository review fixes Android lifecycle/network behavior, while native API integration remains pending. The local bridge configuration disables probes against the existing household stack. Deployment, client integration and service retirement are not part of this commit.

## Next milestone

Integrate the native client into Sofa King and migrate numeric favorites/last-channel keys using the retained legacy join. The Android app now has compatible preview Kotlin pins. Test preview promotion and cancellation on the actual Android TV device, and verify captions/audio before household cutover. Then port provider resolution directly behind the adapter boundary and remove Dispatcharr from the playback path.

Provider-specific limits/credential renewal, shared upstream workers, device pairing, events, archival/ledger retention, and segment persistence/remux remain follow-on slices. Live TV acceptance precedes DVR.
