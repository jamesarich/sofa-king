# Source health

A channel can have multiple stream sources. The server quarantines failing sources without deleting the channel or changing its ID.

## Automatic checks

Probes are enabled by default for configured feeds. `SOFA_PROBE_PARALLELISM` workers (default 6) claim due sources from one queue: suspect, probation and quarantined sources first, then unverified sources, then healthy rechecks, each group oldest attempt first. A source is attempted at most once every five minutes; attempt times persist across restarts. Workers only pause while nothing is due, backing off from one second to 30 seconds.

Each probe has two tiers:

1. HTTP qualification: one GET with an eight-second budget. It must answer 2xx and start with either `#EXTM3U` or three aligned MPEG-TS sync bytes. Any other answer ends the probe and is classified from the HTTP status.
2. FFmpeg decode: 30 seconds of the first video stream with at least 30 frames and 29 seconds of media timestamps; the deadline is 50 seconds. For MPEG-TS sources FFmpeg reconnects on upstream EOF (`-reconnect_at_eof`), so a source that hiccups still qualifies when it delivers 30 seconds within the deadline. HLS sources rely on the HLS demuxer's own segment handling.

Healthy sources recheck after six hours. Unverified and suspect sources retry no sooner than five minutes. Credential/custom-header feeds are not probed because FFmpeg cannot scope their headers by origin.

## Failure classes

| Evidence | Class | Strike |
| --- | --- | --- |
| HTTP 401, 403, or FFmpeg `Server returned 401/403` | `AUTH` | No |
| HTTP 429 or FFmpeg `Server returned 429` | `RATE_LIMIT` | No |
| HTTP 5xx, FFmpeg `Server returned 5XX`, connection refused/reset/timeout, DNS, TLS, or decode deadline exceeded | `PROVIDER` | No |
| HTTP 502 from a configured restream origin (`SOFA_RESTREAM_ORIGINS`) | `OFFERING` | Yes |
| HTTP 404, 410 or other 4xx; 2xx without a stream signature; FFmpeg `Server returned 404` or `4XX`; decode failures; premature stream end | `OFFERING` | Yes |
| Missing decoder, permission, memory or disk errors | `LOCAL` | No |

Restream origins are LAN proxies whose 502 means their upstream is gone. List them as `host` or `host:port`; the scheme's default port applies when a URL has none. The same 502 from any other origin is provider evidence and never strikes.

## States

| State | Available for playback | Meaning |
| --- | --- | --- |
| `UNVERIFIED` | Yes | No qualified result yet |
| `HEALTHY` | Yes | Qualified decode succeeded |
| `SUSPECT` | Yes | Offering-specific failure recorded |
| `QUARANTINED` | No | Three independent failures within six hours, spanning at least ten minutes |
| `PROBATION` | No | Recovery passed once; another pass is required at least ten minutes later |
| `ARCHIVED` | No | Archived source; automatic archival is not implemented |

A suspect source is rechecked ahead of the rest of the queue, so a dead source confirms its quarantine in three probes about five minutes apart. One transient failure leaves a source suspect until its next pass clears it.

Initial quarantine lasts 15 minutes. Failed recovery backs off to one hour, six hours, then one day. Two qualifying recovery passes restore the source. A qualified pass clears all strikes.

Playback selects a healthy source first, then an unverified one, then a suspect one. Quarantined, probation, archived and manually disabled sources are never selected.

## Inspect or disable a source

List source IDs and health states:

```sh
curl --fail http://127.0.0.1:8080/api/v1/sources
```

Disable a source by its ID:

```sh
curl --fail -X PATCH \
  http://127.0.0.1:8080/api/v1/sources/SOURCE_ID \
  -H 'Content-Type: application/json' \
  --data '{"manuallyDisabled":true}'
```

Send `false` to clear the manual override. This preserves existing quarantine/probation state. A manual disable takes precedence over automatic checks and cancels active media requests using that source.

When a source becomes unavailable, GET the playback session to select an eligible alternative. A changed stream has a new descriptor epoch. The player must reopen it.

## Logs

Each probe batch logs its outcome counts by class and duration, and every health transition logs the source ID with its old and new state. Logs never contain stream URLs.
