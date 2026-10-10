# Source health

A channel can have multiple stream sources. The server quarantines failing sources without deleting the channel or changing its ID.

## Automatic checks

Probes are enabled by default for configured feeds. One FFmpeg process runs at a time. A passing probe must decode 30 seconds of video with at least 30 frames and 29 seconds of media timestamps; its deadline is 50 seconds.

Healthy sources recheck after six hours. Unverified and suspect sources retry no sooner than five minutes. Probe attempt times persist across restarts. Credential/custom-header feeds are not probed because FFmpeg cannot scope their headers by origin.

## States

| State | Available for playback | Meaning |
| --- | --- | --- |
| `UNVERIFIED` | Yes | No qualified result yet |
| `HEALTHY` | Yes | Qualified decode succeeded |
| `SUSPECT` | Yes | Offering-specific failure recorded |
| `QUARANTINED` | No | Three independent failures within six hours, spanning at least ten minutes |
| `PROBATION` | No | Recovery passed once; another pass is required at least ten minutes later |
| `ARCHIVED` | No | Archived source; automatic archival is not implemented |

Initial quarantine lasts 15 minutes. Failed recovery backs off to one hour, six hours, then one day. Two qualifying recovery passes restore the source. Auth/rate-limit errors, HTTP 5xx/network errors and recognized local decoder/resource errors do not count as source strikes. Classification uses HTTP and FFmpeg error evidence.

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
