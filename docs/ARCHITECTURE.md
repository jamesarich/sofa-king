# Architecture

Sofa King is one Android module, `app`, written in Kotlin with Compose for TV and Media3.

## Components

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Screen selection, saved guide state, the shared ExoPlayer and the session's stop/start lifecycle |
| `LineupViewModel` | Settings flows, lineup state, refresh, cache ownership and the `PlaybackSessions` instance |
| `SofaKingApi` | The server's `/api/v1` JSON requests over OkHttp with the bearer token and cancellable calls |
| `SofaKingRepository`, `SofaKingMapper` | Catalog and guide queries mapped to `Channel` and `Programme` |
| `PlaybackSessions` | The one playback session: open, promote, renew, release, reopen, replaced streams |
| `LineupCache` | Last complete lineup, keyed by server address |
| `DataStoreSettingsStore` | Server address, API token, favorites, last channel and caption settings |
| `GuideScreen` | Grid, time window, filtering and D-pad navigation |
| `PreviewVideo` | Muted preview on the shared player through a preview session |
| `PlayerScreen` | Full-screen playback, remote commands and MediaSession |

All Kotlin source lives under `dev.jamesrich.sofaking` in [app/src/main/kotlin](../app/src/main/kotlin/dev/jamesrich/sofaking/).

## Data flow

```text
Settings → SofaKingApi → SofaKingRepository → SofaKingMapper
                                             ↓
LineupCache ←→ LineupViewModel → GuideScreen / PlayerScreen
                       ↓
               PlaybackSessions → ExoPlayer (/media relay)
```

The cache loads first on startup or server change. The catalog publishes channels before the guide query completes. The catalog is fetched with its ETag, so an unchanged lineup is a header round trip. The guide is queried in chunks of at most 200 channels over 24 hours from the half hour containing the load time, pinning the revision the first chunk returned and restarting without one on `409 RESYNC_REQUIRED`. Mapping keeps channels with `playbackEligible` and a numeric `displayNumber`, takes the first tag as the group, and discards programmes with invalid timestamps or nonpositive durations. Successful loads update the cache and repeat every three hours. Failed loads retain the available lineup and retry after one minute. Without a valid server address the lineup state is `Unconfigured`.

Network, decoding and cache work run on IO dispatchers. UI observes StateFlow with lifecycle-aware collection. Canceling a load cancels its OkHttp call, including while reading the response body.

## Guide

The grid covers 24 hours from the half-hour at which it opens. It moves the window when the current time leaves that range. Rows share a horizontal scroll position; Up/Down preserve the time cursor. Filter, search, cursor, scroll and focused channel are saved across full-screen playback.

Models are immutable. A shared minute clock updates the guide and current-program displays. Focus requests for lazy items wait until their nodes attach. When guide data arrives after the channels, the focused cell's details are refreshed from the new row and focus returns to the cell at the time cursor.

## Playback

MainActivity owns one ExoPlayer. Preview and full-screen playback share it, so opening the previewed channel preserves the stream. Preview tuning debounces focus by 350 ms. Tune effects wait for the activity to reach STARTED.

Every stream comes from a playback session. `PlaybackSessions` holds at most one: the preview opens it with `preview` intent, and OK on the previewed channel promotes the same session to `watch` with a PATCH, so the player keeps its item and nothing reloads. Tuning another channel releases the held session, then opens the next. The lease is renewed every 15 seconds with an empty PATCH, inside the 45-second preview lease. A renewal that returns a new descriptor epoch, or `410 SESSION_EXPIRED` followed by a reopen, is published on `replaced`; MainActivity swaps the held item's URL and prepares it. A 410 from the media relay is a player error whose first reconnect runs without the backoff delay; tuning again re-reads the session and takes the current URL.

Media items carry the channel id as `mediaId` and the MIME type from the descriptor, translated to Media3's HLS constant, so an HLS playlist is never sniffed as MPEG-TS. Session opens run to completion even when the tune is canceled, and a canceled tune releases what it opened.

ON_STOP and Settings stop playback and release the session; Settings forgets it, ON_STOP remembers the channel and intent. ON_START opens a fresh session for that channel, points the held item at its URL and prepares it while preserving paused intent; if the reopen fails, the stale item is prepared and the screen's reconnect takes over. A new server address or token releases the session before anything tunes on the new server. MainActivity releases the player on disposal. PlayerScreen owns the MediaSession for its composition lifetime. There is no background playback service.

Channel changes stop the player, resolve the session, set the media item and prepare it. Behind-live-window errors seek to the live edge. Other errors and ended streams reconnect with backoff while the player is visible and the activity is started. A session refused with `CHANNEL_NOT_FOUND` is not retried: the player asks the ViewModel to reload the lineup, waits for its catalog, and follows the channel to the row with the same number, then the same name; when neither exists it returns to the guide, which shows a short notice.

The TS extractor declares CEA-608/708 tracks for MPEG-TS sources; HLS sources go through Media3's HLS extractor, which exposes CEA-608 by default. CaptionSync and its load control apply per-channel timing; Compose draws text cues using the selected style. Renderer-thread settings use volatile fields. Video uses Media3 ContentFrame and SurfaceView.

## Server interface

The app uses the [Sofa King server](https://github.com/jamesarich/sofa-king-backend) API: `GET /api/v1/channels` with `If-None-Match`, `POST /api/v1/guide/query`, and `POST`, `GET`, `PATCH` and `DELETE` on `/api/v1/playback-sessions`. API requests carry `Authorization: Bearer TOKEN` when a token is set; media requests under `/media/sessions` carry none. Descriptor URLs are relative and resolved against the server address. The token is stored in DataStore with the other settings and is never logged or printed.
