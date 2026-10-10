# Architecture

Sofa King is a single Android module (`:app`) written in Kotlin with Compose for TV, `androidx.tv.material3` and Media3. It has no backend of its own: it reads the playlist and guide that Dispatcharr already publishes and plays the streams Dispatcharr proxies.

## Packages

All code lives under `dev.jamesrich.sofaking`.

| Package | Contents |
| --- | --- |
| root | `MainActivity` picks the screen (guide, player or settings), holds the header and keeps the guide's saveable state across the player. `LineupViewModel` owns the lineup state, the cache and the refresh loop. |
| `data` | `DispatcharrRepository` fetches the two endpoints. `M3uParser` and `XmltvParser` turn them into models. `Models` holds `Channel`, `Programme` and `Lineup`. `LineupCache` writes the last good lineup to a file and reads it back without ended programs. `SettingsStore` and `DataStoreSettingsStore` persist the server, profile, favorites, last channel and caption settings. `ServerAddress` is the rule Settings applies to an address and a profile. |
| `ui` | `Common` holds the branded message screen, the logo mark, the time formatter and the channel label. `Clock` is the once-a-minute "now". `Focus` retries a focus request until its node is attached. `TvTextField` is the bordered single-line field. `Loader` is `SofaLoader`, the morphing amber busy indicator built on `graphics-shapes`. `Motion` holds the shared enter and exit transitions and `rememberSettled`, which debounces a flickering state before it animates. |
| `ui.guide` | `GuideScreen` owns the grid's state, layout and the Up/Down key handler. `GuideRow` is a channel row and its cells, with `GuideRowEnv` for what every cell shares. `GuideChrome` is the chips, search field, visible-range label and the shared horizontal strip. `GuideTimeline` lays out cells and the 24 h window. `GuideSearch` matches the search query. `GuideMetrics` holds the sizes. |
| `ui.preview` | `PreviewPane` is the details and the muted preview beside the chips; `PreviewVideo` tunes the shared player to the focused channel, muted. |
| `ui.player` | `PlayerScreen` is the full-screen player: its state, key handling and effects. `LivePlayer` builds the app's one player and recovers from a lost live window. `PlayerVideo` is the video surface and the keep-screen-on effect. `CaptionTracks` picks the 708 or 608 track. `Captions` draws them. `CaptionSync`, `CaptionSyncLoadControl`, `SmoothingTextOutput`, `CaptionSmoother` and `CaptionPolicy` are the caption pipeline. `LivePlayerWrapper` is what the `MediaSession` sees. `PlayerOverlays` draws the info bar, status pills, digits and the failure card. `MiniGuide` and `MiniGuideRows` are the channel list over the video. `ChannelNumberEntry`, `CenterKeyGesture`, `PlayerKeys`, `PlayerChannels`, `LiveResume`, `NowPlaying` and `ProgrammeProgress` are small pure helpers with tests. |
| `ui.settings` | `SettingsScreen` edits the server URL, profile and caption settings and shows About. |
| `ui.theme` | `SofaKingTheme`, the dark TV color scheme and typography, the brand colors and the overlay scrims. |

## Data flow

```
Dispatcharr
  /output/m3u/<profile>            playlist    -> M3uParser  -> List<Channel>
  /output/epg/<profile>?days=1     XMLTV       -> XmltvParser -> Map<guideId, List<Programme>>
                                                       |
            LineupCache (filesDir/lineup-cache.bin) <->  LineupViewModel (StateFlow<LineupState>)
                                                       |
                                   MainActivity: Loading / Failed / Ready(Lineup)
                                                       |
                              GuideScreen (grid, filters, preview)  <->  PlayerScreen (ExoPlayer)
```

1. `LineupViewModel` combines the saved server URL and profile with a reload trigger. A change to either restarts the load; a manual refresh restarts it with the lineup already in memory.
2. On a cold start or a server change the cache is read first and shown as `Ready` within a fraction of a second.
3. The playlist loads next. If its channels differ from what is showing, they are emitted with the guide already in hand, so the grid is usable while the large XMLTV parses.
4. The XMLTV guide streams through a pull parser. `XmltvParser` keeps only programs whose `channel` id belongs to the lineup, then the finished `Lineup` replaces the state and is written to the cache.
5. Channel ids in XMLTV are Dispatcharr channel numbers. `Lineup.nowAndNext` picks the current and following program for a channel at a given instant.
6. On success the load repeats every 3 hours. On failure the app retries every minute and keeps the lineup it already has. A failure only shows the failure screen when there is no lineup yet. Cancellation (a refresh or a server change mid-load) is not a failure.
7. `LineupViewModel.loading` is true while a fetch runs, cleared in `finally` so a canceled load never leaves it set; the header shows "Refreshing…" from it while a lineup is on screen.
8. Favorites, the last channel and caption settings are written to DataStore. They do not trigger a reload. A corrupt settings file is replaced with defaults.

## Threading

- Network and parsing run in `withContext(Dispatchers.IO)` inside `DispatcharrRepository`; the cache reads and writes on IO as well. Nothing on the main thread reads a socket or a file.
- `LineupViewModel` runs its load loop in `viewModelScope`. Its flows use `stateIn(..., SharingStarted.Eagerly, ...)`, so the lineup is ready before the first screen asks for it.
- Settings writes launch in `viewModelScope`. The DataStore backing scope uses `Dispatchers.IO`.
- `rememberMinuteNow` ticks once a minute to move the "now" marker, the airing cells, the info bar and the mini-guide. Nothing calls `Instant.now()` during composition.
- `MainActivity` owns the one `ExoPlayer`, created with `remember` and released in a `DisposableEffect`; the full screen adds a `MediaSession` for as long as it shows. `CaptionSync` is written from a `SideEffect` and read on the renderer thread through volatile fields.

## Guide

- The window is 24 h from the half hour the screen opened; it holds while "now" is inside it and rolls over once the clock leaves it. Cells are whole minutes from the window start.
- Every row composes all of its cells across the 24 h strip; the header and every row share one `ScrollState`, which `GuideScreen` moves itself (`revealMinute`). Bring-into-view is disabled on the strip so focus never scrolls it.
- Up and Down are handled before focus search (`onGridKey`): the target row is the next one and the target cell is the one under the time cursor, so a long cell cannot pull focus hours ahead.
- `Channel`, `Programme`, `Lineup` and `GuideCell` are `@Immutable`; `GuideRowEnv` carries strings, the formatter, pixel sizes and callbacks once per guide; a cell formats its accessibility description only inside `semantics {}`. The focused cell is read by the preview band alone.
- Filter, search, the time cursor, scroll and the focused channel are saveable and the guide branch sits in a `SaveableStateHolder`, so Back from the player returns to the same cell. A fresh guide starts on the last channel watched.
- The preview tunes the shared player 350 ms after focus rests on a different stream and plays it muted; a stream the player already holds, such as the channel just watched, keeps playing.

## Playback

- `buildLivePlayer` builds the app's one player: TS extractor flags that allow non-IDR keyframes and detect access units, because Dispatcharr streams are continuous MPEG-TS that can start mid-GOP. It also declares the CEA-608/708 formats, wraps the text renderer for `CaptionSync` and installs `CaptionSyncLoadControl`.
- Each zap calls `stop()`, `setMediaItem` and `prepare()` on the same player; the stop resets the renderers so the next stream's decoder rescales rather than reusing the old surface size.
- The tuned channel is tracked by guide id and number, so a refreshed lineup that reorders or grows keeps the stream and moves the row.
- On `ERROR_CODE_BEHIND_LIVE_WINDOW`, the player seeks to the default position and prepares again. Any other error retries once after 3 s; OK retries again.
- ON_STOP stops the player, freeing the TV's hardware decoder and the Dispatcharr stream; ON_START prepares the same item, which resumes at the live edge or stays paused if the user had paused. OK on the previewed channel hands the playing stream to the full screen without a reload, and Back hands it to the preview.
- The screen stays on only while the player reports `isPlaying`, never during autoplay. The audible player handles audio focus.
- The `MediaSession` lives as long as the player. There is no background playback service.
- Video uses `media3-ui-compose`'s `ContentFrame` (`PlayerVideo.LiveVideo`): a SurfaceView sized to the picture, covered by a shutter from each `stop()` until the next rendered frame. Captions are drawn in Compose (`Captions`): cues from `onCues` become state, each placed at its 608/708 row and column by `CaptionGeometry`, styled from the system caption settings or the high-contrast style, and kept clear of the overscan band. `KeepScreenOn` holds the screen through the host view while playing.

## Key decisions

- **Dispatcharr is the only source.** The app does not talk to any other service, so the server's own channel profile decides what appears on the TV.
- **Cache, then channels, then guide.** The last lineup is on screen at once; the playlist is small and parses quickly; the guide is the slow part on a Chromecast with Google TV.
- **One day of guide.** The default XMLTV window is about 16 MB and one day is about 6.6 MB. A day is enough for the grid and parses about 2.5 times faster.
- **Lineup only.** Programs for channels outside the profile are dropped during parsing, so memory tracks the lineup, not the server's full guide.
- **Compose for TV only.** Components come from `androidx.tv.material3`. Mobile Material 3 is not mixed in. Lists use foundation `LazyColumn`, `LazyRow` and `LazyVerticalGrid`.
- **D-pad first.** Every lazy container has `focusRestorer`, and focus is requested only from `LaunchedEffect` or a callback, through `requestFocusWhenAttached` for lazy items. Back returns toward the channel list.
- **Overscan padding.** Full-screen content keeps 48dp horizontal and 27dp vertical padding; the app header carries the top margin above the guide.
- **No background playback.** No service keeps a stream alive outside the player screen, and a stopped activity holds no decoder. The `MediaSession` exists only while the player does.
- **Guide in the app.** The Google TV Live tab and the TV Input Framework are not open to third-party apps, so the guide lives inside Sofa King.
- **Brand on every screen.** Plum background, amber for focus and emphasis, lavender for the mark; overlays over video use a plum scrim chosen for contrast over any picture.

## Testing

Parser, cache, settings and UI logic run as JVM unit tests under `app/src/test`. The XML tests use kxml2 in place of Android's `XmlPullParser`; the DataStore tests use a temporary file. `app/src/test/resources/` holds a 20-channel snapshot of a real profile, so parser tests run without a server.
