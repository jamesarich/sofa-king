# Architecture

Sofa King is a Kotlin Multiplatform project. One Compose Multiplatform UI on Material 3 Expressive runs on Android TV, Google TV and Android phones, on Linux, macOS and Windows desktops, on iOS, and in browsers through Wasm or JS.

## Modules

| Module | What it holds | Targets |
| --- | --- | --- |
| `core` | Models, the server API, repository, playback sessions, lineup cache, settings store and `LineupViewModel`. No Compose. | Android, JVM, iOS, macOS, tvOS, watchOS, Linux, js, wasmJs |
| `ui` | Every screen, the app shell, the guide and player logic, the Koin graph, and the desktop, web and iOS entry points | Android, JVM, iOS, js, wasmJs |
| `androidApp` | The Android application: manifest, launcher and TV banner, `MainActivity` | Android |
| `iosApp` | The SwiftUI host for the `SofaKingUi` framework, as an XcodeGen spec | iOS |

`core` targets everything its dependencies publish, so a native host such as a SwiftUI tvOS app can use the same data layer without Compose. Windows native is absent because DataStore has no mingw build; the JVM covers Windows desktops.

All source lives under `dev.jamesrich.sofaking`. Code is in `commonMain` unless it needs a platform API; each `expect` has one `actual` per platform source set (`androidMain`, `jvmMain`, `iosMain`, `webMain`).

## Components

| Component | Responsibility |
| --- | --- |
| `App`, `initSofaKing` | Screen selection and the cold-start decision, saved guide state, the shared player and the session's stop/start lifecycle; the graph starts once per process |
| `LineupViewModel` | Settings flows, lineup state, refresh, cache ownership and the `PlaybackSessions` instance; its store, cache and API factory are constructor arguments |
| `SofaKingApi` | The server's `/api/v1` JSON requests over Ktor with the bearer token; every engine's transport error surfaces as an `IOException` |
| `SofaKingRepository`, `SofaKingMapper` | Catalog and guide queries mapped to `Channel` (with its logo and guide status) and `Programme` (with its episode, rating, new flag and image) |
| `PlaybackSessions` | The one playback session: open, promote, renew, release, reopen, replaced streams |
| `LineupCache`, `ByteStore` | Last complete lineup, keyed by server address, in a file or the browser's localStorage |
| `DataStoreSettingsStore`, `AppStorage` | Server address, API token, favorites, last channel, caption and preview settings |
| `GuideScreen` | Grid, time window, filtering and D-pad navigation |
| `PreviewVideo` | Muted preview on the shared player through a preview session |
| `PlayerScreen` | Full-screen playback, remote commands and the system media session |
| `LivePlayer` | The playback contract each platform implements |

## Data flow

```text
AppStorage → DataStoreSettingsStore → LineupViewModel ← SofaKingRepository ← SofaKingApi (Ktor)
                                           ↓      ↕
                                     LineupCache   PlaybackSessions → LivePlayer (/media relay)
                                           ↓
                              GuideScreen / PreviewVideo / PlayerScreen
```

The cache loads first on startup or server change. The catalog publishes channels before the guide query completes. The catalog is fetched with its ETag, so an unchanged lineup is a header round trip. The guide is queried in chunks of at most 200 channels over 24 hours from the half hour containing the load time, pinning the revision the first chunk returned on the rest, which run four at a time, and restarting without one on `409 RESYNC_REQUIRED`. A channel the guide answers as `removed` left the lineup after the catalog was read: it drops from the lineup and the next load runs after one minute. Mapping keeps channels with `playbackEligible` and a numeric `displayNumber`, files the channel under every non-blank tag (Other when it has none), resolves each logo's server-relative path against the configured server address, and discards programs with invalid timestamps or nonpositive durations. Successful loads update the cache and repeat every three hours. Failed loads retain the available lineup and retry after one minute. Without a valid server address the lineup state is `Unconfigured`.

Network, decoding and cache work run on the IO dispatcher (the default dispatcher on the web). UI observes StateFlow with lifecycle-aware collection. Canceling a load cancels its HTTP call, including while reading the response body.

## Storage

| Platform | Settings | Lineup cache |
| --- | --- | --- |
| Android | `files/datastore/settings.preferences_pb` | `files/lineup-cache.bin` |
| Desktop | DataStore under the OS's app data directory (`~/.local/share/sofa-king`, `~/Library/Application Support/Sofa King`, `%APPDATA%\Sofa King`) | `lineup-cache.bin` beside it |
| iOS | DataStore under Application Support | `lineup-cache.bin` beside it |
| Web | localStorage, one JSON entry | localStorage, base64 |

DataStore allows one instance per file, so the graph holds it as a single and each entry point starts the graph once. The lineup cache is a versioned binary layout (big-endian numbers, length-prefixed UTF-8) that every platform reads and writes the same way.

## Launch

A cold start shows the loader until the lineup state and settings are both known, then decides once: the last watched channel opens full screen through the normal session path when the lineup has it; a first run, a channel the lineup lost, an unconfigured or unreachable server open the guide. The guide and its preview do not compose before that decision, so no preview session is opened only to be replaced. Back from that player shows the guide focused on the channel being watched.

## UI

Screens use multiplatform Material 3 Expressive components (`Button`, `Switch`, `FilterChip`, `Surface`, `OutlinedTextField`, `LoadingIndicator`) under `MaterialExpressiveTheme` with the expressive motion scheme. `tvFocus` adds the D-pad focus cue, a scale and an outline ring; guide cells keep their size and change color instead. Strings are Compose resources in US English. Channel logos and program images load through the API's connection pool (`ImageFetch`), decode with Compose and stay in a small memory cache. Back goes through NavigationEvent: the remote's Back on Android, the swipe on iOS, Escape on desktops and the web.

## Guide

The grid covers 24 hours from the half-hour at which it opens. It moves the window when the current time leaves that range. Rows share a horizontal scroll position; Up/Down preserve the time cursor. Filter, search, cursor, scroll and focused channel are saved across full-screen playback. Voice search goes through Android's `RecognizerIntent` activity; the manifest declares the query so the app can tell whether one is installed, and the text field is the fallback there and on every other platform.

Models are immutable; the UI module's Compose stability configuration marks the core models stable. A minute clock that ticks on the wall-clock minute updates the guide and current-program displays. Focus requests for lazy items wait until their nodes attach. When guide data arrives after the channels, the focused cell's details are refreshed from the new row and focus returns to the cell at the time cursor.

## Playback

The app holds one `LivePlayer`. Preview and full-screen playback share it, so opening the previewed channel preserves the stream. Preview tuning debounces focus by 350 ms. Tune effects wait for the host to reach STARTED. `PreviewDwell` holds the preview's rest timers on a monotonic clock: muted on tune, sound after 5 s, full screen after 10 s through the same promote handoff as OK; a key press or a focus move restarts both and mutes, focus off the grid or an open search stops them, nothing opens while the host is stopped and ON_START restarts the rest, and the channel the player was just left on is suppressed until another channel takes focus.

| Platform | Player | Plays | Captions | Media session |
| --- | --- | --- | --- | --- |
| Android | Media3 ExoPlayer | MPEG-TS, HLS | CEA-608/708, drawn by the app with per-channel timing | MediaSession |
| Desktop | libVLC through vlcj | MPEG-TS, HLS | Drawn into the picture by libVLC | None |
| iOS | AVPlayer | HLS | Drawn by AVPlayerLayer in the iOS caption style | Now Playing and remote commands |
| Web | `<video>`, drawn into the Compose canvas through WebGL | HLS where the browser plays it natively | The browser's in-band text tracks, drawn by the app; Safari exposes CEA-608, Chrome's built-in HLS exposes none | Page title |

On the web the `<video>` element stays out of the page: each decoded frame is uploaded into a texture of its own size and blitted into a `WebGLRenderTarget`, which draws as a Compose image, so overlays draw over the picture. Browsers refuse autoplay with sound before a user gesture; the player then plays muted and restores the sound on the first key or pointer press. Without WebGL2, or when a cross-origin server sends no CORS headers on media, the player falls back to an HTML element view, which Compose stacks above its canvas and so hides the full-screen overlays.

`LivePlayer` exposes state as flows (`status`, `errors`, `cues`), so screens collect them instead of registering listeners. A desktop without libVLC gets a player whose every prepare fails with `VLC_NOT_INSTALLED`, so the screens show their failure cards and the rest of the app works.

Every stream comes from a playback session. `PlaybackSessions` holds at most one: the preview opens it with `preview` intent, and OK on the previewed channel promotes the same session to `watch` with a PATCH, so the player keeps its item and nothing reloads. Tuning another channel releases the held session, then opens the next. The lease is renewed every 15 seconds with an empty PATCH, inside the 45-second preview lease. A renewal that returns a new descriptor epoch, or `410 SESSION_EXPIRED` followed by a reopen, is published on `replaced`; the app swaps the held item's URL and prepares it. A 410 from the media relay is a player error whose first reconnect runs without the backoff delay; tuning again re-reads the session and takes the current URL.

Items carry the channel id and the MIME type from the descriptor, translated to Media3's HLS constant, so Media3 never sniffs an HLS playlist as MPEG-TS. Session opens run to completion even when the tune is canceled, and a canceled tune releases what it opened.

ON_STOP and Settings stop playback and release the session; Settings forgets it, ON_STOP remembers the channel and intent. ON_START opens a fresh session for that channel, points the held item at its URL and prepares it while preserving paused intent; if the reopen fails, the stale item is prepared and the screen's reconnect takes over. A new server address or token releases the session before anything tunes on the new server. The app releases the player on disposal. `PlayerScreen` holds the media session for its composition lifetime. There is no background playback service.

Channel changes update the info bar at once and tune once presses have rested for 400 ms; the tune stops the player, resolves the session, sets the item and prepares it. Falling behind the live window re-prepares at the live edge inside the player. Other errors and ended streams reconnect with backoff while the player is visible and the host is started. A session refused with `CHANNEL_NOT_FOUND` is not retried: the player asks the ViewModel to reload the lineup, waits for its catalog, and follows the channel to the row with the same number, then the same name; when neither exists it returns to the guide, which shows a short notice. A reload that fails or times out is retried with the reconnect backoff; the dead id is never asked for again except by OK.

On Android the TS extractor declares CEA-608/708 tracks for MPEG-TS sources; HLS sources go through Media3's HLS extractor, which exposes CEA-608 by default. `CaptionSync` and its load control apply per-channel timing, and the player picks the preferred text track on every track change. Media3 cues become `CaptionCue`s, whose positioning constants equal Media3's, and the common `Captions` composable draws them in the selected style on the full screen and over the preview. Renderer-thread settings use volatile fields. Video uses Media3 `ContentFrame` and a SurfaceView.

## Server interface

The app uses the [Sofa King server](../../backend/README.md) API through the request and response types in [`contract/`](../../contract/README.md), which the server compiles against too: `GET /api/v1/channels` with `If-None-Match`, `POST /api/v1/guide/query`, and `POST`, `GET`, `PATCH` and `DELETE` on `/api/v1/playback-sessions`. API requests carry `Authorization: Bearer TOKEN` when a token is set; media requests under `/media/sessions` carry none. Descriptor URLs are relative and resolved against the server address. The token is stored with the other settings and is never logged or printed.
