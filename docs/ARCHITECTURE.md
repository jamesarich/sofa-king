# Architecture

Sofa King is one Android module, `app`, written in Kotlin with Compose for TV and Media3.

## Components

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Screen selection, saved guide state and the shared ExoPlayer |
| `LineupViewModel` | Settings flows, lineup state, refresh and cache ownership |
| `DispatcharrRepository` | M3U/XMLTV HTTP requests with cancellable OkHttp calls |
| `M3uParser`, `XmltvParser` | Playlist and guide parsing |
| `LineupCache` | Last complete lineup, keyed by server and profile |
| `DataStoreSettingsStore` | Server, profile, favorites, last channel and caption settings |
| `GuideScreen` | Grid, time window, filtering and D-pad navigation |
| `PreviewVideo` | Muted preview on the shared player |
| `PlayerScreen` | Full-screen playback, remote commands and MediaSession |

All Kotlin source lives under `dev.jamesrich.sofaking` in [app/src/main/kotlin](../app/src/main/kotlin/dev/jamesrich/sofaking/).

## Data flow

```text
Settings → DispatcharrRepository → M3U/XMLTV parsers
                                  ↓
LineupCache ←→ LineupViewModel → GuideScreen / PlayerScreen
```

The cache loads first on startup or server change. The playlist publishes channels before the XMLTV load completes. Guide parsing keeps only lineup channels and discards invalid timestamps or nonpositive durations. Successful loads update the cache and repeat every three hours. Failed loads retain the available lineup and retry after one minute.

Network, parsing and cache work run on IO dispatchers. UI observes StateFlow with lifecycle-aware collection. Canceling a load cancels its OkHttp call, including while reading the response body.

## Guide

The grid covers 24 hours from the half-hour at which it opens. It moves the window when the current time leaves that range. Rows share a horizontal scroll position; Up/Down preserve the time cursor. Filter, search, cursor, scroll and focused channel are saved across full-screen playback.

Models are immutable. A shared minute clock updates the guide and current-program displays. Focus requests for lazy items wait until their nodes attach.

## Playback

MainActivity owns one ExoPlayer. Preview and full-screen playback share it, so opening the previewed channel preserves the stream. Preview tuning debounces focus by 350 ms. Tune effects wait for the activity to reach STARTED.

ON_STOP and Settings stop playback; ON_START prepares the current item while preserving paused intent. MainActivity releases the player on disposal. PlayerScreen owns the MediaSession for its composition lifetime. There is no background playback service.

Channel changes stop the player, set the media item and prepare it. Behind-live-window errors seek to the live edge. Other errors and ended streams reconnect with backoff while the player is visible and the activity is started.

The TS extractor declares CEA-608/708 tracks. CaptionSync and its load control apply per-channel timing; Compose draws text cues using the selected style. Renderer-thread settings use volatile fields. Video uses Media3 ContentFrame and SurfaceView.

## Server interface

The app reads profile M3U/XMLTV endpoints and the playlist's stream URLs. It does not use the [Sofa King Backend](https://github.com/jamesarich/sofa-king-backend) JSON or playback-session API.
