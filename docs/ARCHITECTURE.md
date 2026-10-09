# Architecture

Sofa King is a single Android module (`:app`) written in Kotlin with Compose for TV, `androidx.tv.material3` and Media3. It has no backend of its own: it reads the playlist and guide that Dispatcharr already publishes and plays the streams Dispatcharr proxies.

## Packages

All code lives under `dev.jamesrich.sofaking`.

| Package | Contents |
| --- | --- |
| root | `MainActivity` picks the screen (guide, player or settings) and holds the header. `LineupViewModel` owns the lineup state and the refresh loop. |
| `data` | `DispatcharrRepository` fetches the two endpoints. `M3uParser` and `XmltvParser` turn them into models. `Models` holds `Channel`, `Programme` and `Lineup`. `SettingsStore` and `DataStoreSettingsStore` persist the server, profile, favorites and last channel. |
| `ui` | `Common` holds shared message screens. `PlayerScreen` is the full-screen player. |
| `ui.guide` | `GuideScreen` is the grid and its filters. `GuideTimeline` lays out the cells. |
| `ui.player` | `ChannelNumberEntry` handles typed channel numbers. `ProgrammeProgress` computes how far through a programme the current time is. |
| `ui.settings` | `SettingsScreen` edits the server URL and profile. |
| `ui.theme` | `SofaKingTheme`, the dark TV color scheme and typography, and the brand colors. |

## Data flow

```
Dispatcharr
  /output/m3u/<profile>            playlist    -> M3uParser  -> List<Channel>
  /output/epg/<profile>?days=1     XMLTV       -> XmltvParser -> Map<guideId, List<Programme>>
                                                       |
                                   LineupViewModel (StateFlow<LineupState>)
                                                       |
                                   MainActivity: Loading / Failed / Ready(Lineup)
                                                       |
                              GuideScreen (grid, filters)  <->  PlayerScreen (ExoPlayer)
```

1. `LineupViewModel` combines the saved server URL and profile with a reload trigger. A change to either restarts the load.
2. The playlist loads first. Its channels are emitted as `Ready` with an empty guide, so the grid appears within a few seconds.
3. The XMLTV guide streams through a pull parser. `XmltvParser` keeps only programs whose `channel` id belongs to the lineup, then the finished `Lineup` replaces the state.
4. Channel ids in XMLTV are Dispatcharr channel numbers. `Lineup.nowAndNext` picks the current and following program for a channel at a given instant.
5. On success the load repeats every 3 hours. On failure the app retries every minute and keeps the lineup it already has. A failure only shows the failure screen when there is no lineup yet.
6. Favorites and the last channel are written to DataStore. They do not trigger a reload.

## Threading

- Network and parsing run in `withContext(Dispatchers.IO)` inside `DispatcharrRepository`. Nothing on the main thread reads a socket.
- `LineupViewModel` runs its load loop in `viewModelScope`. Its flows use `stateIn(..., SharingStarted.Eagerly, ...)`, so the lineup is ready before the first screen asks for it.
- Settings writes launch in `viewModelScope`. The DataStore backing scope uses `Dispatchers.IO`.
- The guide ticks once a minute to move the "now" marker. That tick only changes UI state.
- The player owns one `ExoPlayer` per player screen. It is created with `remember` and released in a `DisposableEffect`, along with its `MediaSession`.

## Playback

- Each zap calls `setMediaItem` and `prepare` on the same player.
- The TS extractor allows non-IDR keyframes and detects access units. Dispatcharr streams are continuous MPEG-TS that can start mid-GOP, so this lets playback start without waiting for an IDR frame.
- On `ERROR_CODE_BEHIND_LIVE_WINDOW`, the player seeks to the default position and prepares again.
- The screen stays on only while the player reports `isPlaying`, never during autoplay.
- The `MediaSession` lives as long as the player. There is no background playback service.
- Video uses `PlayerView` inside `AndroidView`. The `media3-ui-compose` `ContentFrame` is `@UnstableApi`, so the app does not use it.

## Key decisions

- **Dispatcharr is the only source.** The app does not talk to any other service, so the server's own channel profile decides what appears on the TV.
- **Two-phase load.** Channels first, guide second. The playlist is small and parses quickly, and the guide is the slow part on a Chromecast with Google TV.
- **One day of guide.** The default XMLTV window is about 16 MB and one day is about 6.6 MB. A day is enough for the grid and parses about 2.5 times faster.
- **Lineup only.** Programs for channels outside the profile are dropped during parsing, so memory tracks the lineup, not the server's full guide.
- **Compose for TV only.** Components come from `androidx.tv.material3`. Mobile Material 3 is not mixed in. Lists use foundation `LazyColumn`, `LazyRow` and `LazyVerticalGrid`.
- **D-pad first.** Every lazy container has `focusRestorer`, and focus is requested only from `LaunchedEffect` or a callback. Back returns toward the channel list.
- **Overscan padding.** Full-screen content keeps 48dp horizontal and 27dp vertical padding.
- **No background playback.** No service keeps a stream alive outside the player screen. The `MediaSession` exists only while the player does.
- **Guide in the app.** The Google TV Live tab and the TV Input Framework are not open to third-party apps, so the guide lives inside Sofa King.

## Testing

Parser and model logic runs as JVM unit tests under `app/src/test`. The XML tests use kxml2 in place of Android's `XmlPullParser`. `app/src/test/resources/` holds a 20-channel snapshot of a real profile, so parser tests run without a server.
