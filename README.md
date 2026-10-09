# Sofa King

Live TV for the living room. Sofa King is an Android TV and Google TV app that plays the channels from your own [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) server, with a TV guide and a full-screen player you drive with the remote.

_Live TV, right where you left it._

## Features

- Guide grid with Now, Search, All and Favorites chips, plus category filters from your channel groups.
- A live, muted preview of the focused channel above the guide.
- The guide opens on the channel you watched last, and Back from the player returns to the cell you left.
- Long-press a program to star its channel.
- Full-screen player: Up and Down zap, OK shows the info bar and Down from there opens a channel list with the last channel on top, long-press OK returns to the last channel.
- Closed captions with per-channel timing to offset live-captioning delay.
- Media keys and system media controls: next/previous channel, play and pause.
- The last lineup shows the moment the app opens; the lineup and guide refresh every 3 hours, or on demand from Refresh, with "Refreshing…" in the header while they load.
- Channels appear first, then the guide fills in behind them.
- Dispatcharr URL and channel profile are set in Settings and stored on the device.

## Screenshots

_Screenshots to come._

## Build and install

```
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Install the release build on a TV. It is R8-optimized and signed with the local debug key; a debug build runs Compose unoptimized and is several times slower on streaming-stick hardware.

The default server and profile come from Gradle properties:

```
./gradlew :app:assembleRelease -PdispatcharrUrl=http://192.168.1.3:9191 -PdispatcharrProfile=livingroom
```

Both values can also be changed on the TV from Settings.

### Enabling ADB on a Google TV

1. Open Settings > System > About and press Android TV OS build seven times to enable developer options.
2. Open Settings > System > Developer options and turn on Wireless debugging.
3. Choose Pair device with pairing code, then run `adb pair <ip:port> <code>` and `adb connect <ip:port>` from your computer.
4. Run the `adb install` command above.

## Remote controls

| Key | Guide | Player |
| --- | --- | --- |
| D-pad Up | Move focus | Next channel |
| D-pad Down | Move focus | Previous channel; with the info bar up, open the channel list (last channel first): Up/Down move, OK tunes, Back closes |
| D-pad Left / Right | Move focus | Toggle the info bar; with captions on and the info bar up, show this channel's captions later / earlier |
| OK | Play the channel | Toggle the info bar, tune a typed number, or retry a channel that failed |
| Long-press OK | Star or unstar the channel | Return to the last channel watched |
| Channel Up / Down | Move focus | Next / previous channel |
| Captions (CC) | | Turn captions on or off |
| Play/Pause | | Pause or resume; resuming after 30 s jumps back to live |
| Digits 0-9 (and numpad) | | Type a channel number, then OK to tune |
| Previous / Last channel | | Return to the last channel watched |
| Info | | Toggle the info bar |
| Back | Exit the app | Close the channel list, or return to the guide |

The Now chip returns the guide to the current time; Search filters by channel name, number or what's on. While a channel plays, the media session maps next/previous to channel up/down and supports play and pause, so system media controls and Assistant transport commands can drive it; voice phrasing beyond "pause" is untested. When another app comes to the front, playback stops and frees the TV's decoder; coming back resumes at the live edge.

## Dispatcharr setup

Sofa King reads two endpoints from Dispatcharr.

- **Server URL.** The base address of your Dispatcharr, including the port, for example `http://192.168.1.3:9191`. The TV must be on the same network as the server. Plain HTTP on the LAN is allowed. Settings only saves an address that starts with `http://` or `https://`.
- **Channel profile.** The name of the Dispatcharr channel profile that holds the channels you want on the TV. The app fetches `/output/m3u/<profile>` for the lineup and `/output/epg/<profile>?days=1` for the guide. Only channels in that profile appear.

The guide matches programs to channels by channel number, so the profile's channels need EPG data assigned in Dispatcharr. Channels without programs show "No listing".

Streams play through Dispatcharr's `/proxy/ts/stream/` path, so the TV only needs to reach the Dispatcharr server.

## Troubleshooting

- **"Couldn't reach Dispatcharr".** Check the server URL in Settings, confirm the TV and server are on the same network, and open the URL in a browser on another device on that network. The line under the headline is the network error. Refresh retries the load; a lineup loaded earlier keeps showing until it succeeds.
- **"No channels in this profile".** The profile name does not match a profile in Dispatcharr, or the profile has no channels. Check the spelling in Settings.
- **"No listing" on a channel.** Dispatcharr has no program data for that channel. Assign an EPG source to the channel in Dispatcharr.
- **"Can't play this channel".** The stream is not reachable through Dispatcharr. The app retries once on its own; OK retries again. Check that the channel plays in Dispatcharr, then try another channel.
- **The guide takes a moment to fill.** A saved lineup appears at once; a fresh load from the server takes a few seconds for a large profile. Channels appear first, and the guide fills in behind them.
- **Playback starts a moment after tuning.** Streams can start mid-GOP, so the first frames may take a second to decode. With captions on, tuning waits for the channel's caption timing to buffer.

## Development

- [`CLAUDE.md`](./CLAUDE.md) holds the rules the code follows, including focus, overscan and playback constraints.
- [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) describes the packages, the data flow and the threading model.
- `tools/render-banner.sh` regenerates the Google TV banner from the vector mark (ImageMagick and Noto Sans).

## License

Sofa King is free software under the GNU General Public License v3.0. See [`LICENSE`](./LICENSE).
