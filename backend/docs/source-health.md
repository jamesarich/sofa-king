# Source health

A lineup entry can list multiple stream sources, from one provider or several. The server quarantines failing sources without deleting the entry or changing its ID.

## Automatic checks

Without a lineup policy, the sources of every provider with `probe` set (the default) are probed. Under a policy, the sources its stations use are probed, whatever `probe` says, in the tiers [probing](lineup-policy.md#probing) describes. `SOFA_PROBE_PARALLELISM` workers (default 6) claim due sources from one queue: suspect, probation and quarantined sources first, then unverified sources, then healthy rechecks, each group oldest attempt first. A source is attempted at most once every five minutes; attempt times persist across restarts. Workers only pause while nothing is due, backing off from one second to 30 seconds.

Each probe has two tiers:

1. HTTP qualification: one GET with an eight-second budget. It must answer 2xx and start with either `#EXTM3U` or three aligned MPEG-TS sync bytes. Any other answer ends the probe and is classified from the HTTP status. The signature found is stored as the source's protocol, replacing the one the import assigned, so playback descriptors and the decode tier use it from then on, including after a restart.
2. FFmpeg decode: 30 seconds of the first video stream with at least 30 frames and 29 seconds of media timestamps; the deadline is 50 seconds. For MPEG-TS sources FFmpeg reconnects on upstream EOF (`-reconnect_at_eof`), so a source that hiccups still qualifies when it delivers 30 seconds within the deadline. HLS sources rely on the HLS demuxer's own segment handling.

At start the server deletes the stored data of what the providers do not carry. A provider missing from the configuration whose last import is over 30 days old loses its stored snapshot. A source no stored snapshot lists, whose health last changed over 30 days ago, loses its health, observations, probe attempt and detected protocol. A source disabled by hand keeps its row. With no provider configured nothing is deleted.

Healthy sources recheck after six hours. A recheck of a healthy source copies the video packets instead of decoding them (`-c copy`). It needs only the 29 seconds of media timestamps, since FFmpeg 6 reports no frame count for a copy. It proves the source still delivers at a fraction of the CPU, and a source that fails it is fully decoded again on its next probe. Unverified and suspect sources retry no sooner than five minutes. FFmpeg reads the source through the server's loopback relay, which checks every hop against the [address policy](live-tv.md#upstream-addresses) and sends the provider headers; it may open only the `http`, `tcp` and `crypto` protocols. The relay passes the upstream's error status on, so the classes below read FFmpeg's `Server returned` lines the same way, and a hop to a refused address answers `422`, which counts against the source.

## Failure classes

| Evidence | Class | Strike |
| --- | --- | --- |
| HTTP 401, 403, or FFmpeg `Server returned 401/403` | `AUTH` | No, unless another source of the provider qualified since this one was last refused |
| HTTP 429 or FFmpeg `Server returned 429` | `RATE_LIMIT` | No |
| HTTP 5xx, FFmpeg `Server returned 5XX`, connection refused/reset/timeout, DNS, TLS, or decode deadline exceeded | `PROVIDER` | No |
| HTTP 502 from a configured restream origin (`SOFA_RESTREAM_ORIGINS`) | `OFFERING` | Yes |
| HTTP 404, 410 or other 4xx; 2xx without a stream signature; FFmpeg `Server returned 404` or `4XX`; decode failures; premature stream end | `OFFERING` | Yes |
| Missing decoder, permission, memory or disk errors | `LOCAL` | No |

`OFFERING` evidence points at the source itself, so only it strikes. A refusal is the provider's while its credentials fail everywhere, but once another source of the same provider has qualified since a source was last refused, the refusal is that source's own, such as a channel geo-blocked from the server's country or behind a partner's dead token, and it is recorded as `OFFERING`. A credential outage stops every source of the provider qualifying, so it never quarantines them. The server keeps these times in memory, so after a restart a source's first refusal does not strike. Restream origins are LAN proxies whose 502 means their upstream is gone. List them as `host` or `host:port`; the scheme's default port applies when a URL has none. The same 502 from any other origin is provider evidence and never strikes.

## States

| State | Available for playback | Meaning |
| --- | --- | --- |
| `UNVERIFIED` | Yes | No qualified result yet |
| `HEALTHY` | Yes | Qualified decode succeeded |
| `SUSPECT` | Yes | A failure that points at this source was recorded |
| `QUARANTINED` | No | Three independent failures within six hours, spanning at least ten minutes |
| `PROBATION` | No | Recovery passed once; another pass is required at least ten minutes later |
| `ARCHIVED` | No | Archived source; automatic archival is not implemented |

A suspect source is rechecked ahead of unverified and healthy sources, no sooner than five minutes after its last attempt, so three strikes land inside the six-hour window whenever one round over the suspect queue takes under two hours. One transient failure leaves a source suspect until its next pass clears it.

Initial quarantine lasts 15 minutes. Failed recovery backs off to one hour, six hours, then one day. Two qualifying recovery passes restore the source. A qualified pass clears all strikes.

## Source selection

Every playback session, API, player or HDHomeRun, takes the lineup entry's first selectable source by health rank: healthy first, then unverified, then suspect, in the entry's failover order within each rank. Quarantined, probation, archived, manually disabled and withdrawn sources are never selected. When the playing source stops being selectable, an API session moves to the next one the next time the client fetches it, as [open and release playback](api.md#open-and-release-playback) describes.

Switch on the portal's Watching now (or `POST /api/v1/admin/sessions/{id}/switch-source`) moves a session to the channel's next selectable source by hand, for a picture that plays but looks wrong. It passes over the source playing and those the session was switched away from, so pressing it again turns through every source; once none is left it starts over. The session keeps its id and moves to a new epoch: the app re-reads it and plays the new source within a second or two, while an M3U, Plex or HDHomeRun player sees its stream end once and opens it again. A channel with no other selectable source refuses with `409 NO_OTHER_SOURCE`. Watching now also shows each session's rate, its stalls (media requests the source refused or dropped) and its switches, the automatic ones included. The app's stream card shows the same three for its own session, from the `health` a read or renewal of the session answers with.

### Stall and quality failover

A live HLS source that stops adding segments has stalled: its media playlist shows the same newest segment for two of its target durations (the target counted between 2 and 30 seconds), or lists no segments at all. Playback then moves to the channel's next selectable source the session has not played, and counts a switch:

- An app or M3U player session gets `410 STREAM_CHANGED` on that playlist fetch, re-reads the session and plays the new source, as after Switch.
- A remuxed stream (Plex, HDHomeRun and `format=ts` players) moves inside the same response: FFmpeg stops on the stalled source and starts on the next, and the player sees one break in the stream rather than its end.

A session never goes back to a source it failed over from, so when none is left it stays on the one it plays. The server logs each move as `playback failover` with the reason and both providers.

`SOFA_QUALITY_FLOORS=true` adds two floors for remuxed streams: a picture that stays black for 30 seconds and a rate under 64 kbit/s for 30 seconds, from the start of the stream on, each move the stream the same way. FFmpeg checks the picture on the video's keyframes beside the copy, which stays untouched. A source with no video plays without the check.

With a [lineup policy](lineup-policy.md), a station none of whose sources is selectable for `health.disableAfterHours` (default 24) turns off, and the M3U, XMLTV and HDHomeRun listings stop carrying it. A source qualifying `HEALTHY` again turns it back on, or any selectable source with `health.enableOn: eligible`.

## Inspect or disable a source

The admin portal's [Channel page](admin.md#pages) disables, enables and probes a source with a click, and shows each source's last three probe results with their failure classes in words. These routes disable and enable with the API token; a probe on request has no API route. With Docker the server is at `SERVER_HOST:9193`; outside Docker the port is 8080. List source IDs and health states:

```sh
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" \
  http://SERVER_HOST:9193/api/v1/sources
```

Disable a source by its ID:

```sh
curl --fail -X PATCH -H "Authorization: Bearer $SOFA_ADMIN_TOKEN" \
  http://SERVER_HOST:9193/api/v1/sources/SOURCE_ID \
  -H 'Content-Type: application/json' \
  --data '{"manuallyDisabled":true}'
```

Send `false` to clear the manual override. This preserves existing quarantine/probation state. A manual disable takes precedence over automatic checks and cancels active media requests using that source.

When a source becomes unavailable, GET the playback session to select an eligible alternative. A changed stream has a new descriptor epoch. The player must reopen it.

## Logs

Probe batches and health transitions are logged as [logs](operations.md#logs) describes, never with stream URLs.
