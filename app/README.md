# Sofa King

Live TV, Sofa King good.

Sofa King is a live TV app for the channels of your own [Sofa King server](../backend/README.md). It is built for Android TV and Google TV, and the same app runs on Android phones, Linux, macOS and Windows desktops, iOS and the web. Channels, the guide and the streams come from that server; the app includes no channel subscription.

## Why

- **Made for a remote.** Every screen works with the five-way D-pad, Back, and the Guide, Menu, Search, channel and digit keys. A keyboard, touch or a mouse work too.
- **Previews that become the show.** The focused channel plays muted in the guide, its sound comes on after 5 seconds, and it opens full screen after 10 on the same stream, with no second tune.
- **Back where you left off.** A cold start opens the last channel you watched, and Back from the player lands on that channel in the guide with your filter and scroll intact.
- **Light on the server.** The app holds one playback session at a time, renews it while you watch and releases it when you leave, so a small server's few slots go further.
- **Live captions that keep up.** CEA-608 and CEA-708 captions, with timing you can set per channel to line delayed live captions up with the speech.
- **One codebase.** Kotlin Multiplatform and Compose Multiplatform on every platform, with each platform's own player underneath.

## Features

- A program guide covering 24 hours, with category and favorite filters, search and voice search
- Muted previews that open full screen after 10 seconds, 30 seconds or never ([using Sofa King](docs/usage.md#guide))
- Favorites, and the last watched channel restored on launch
- A full-screen player with channel up and down, number entry, the previous channel and a channel list
- Closed captions, with per-channel timing on Android and a style setting where the app draws them ([captions](docs/usage.md#captions))
- A saved lineup that shows at once while the app refreshes from the server
- Reconnection with backoff when a stream fails, and the channel followed by number, then name, when the server's lineup drops its id

## Platforms

| Platform | Runs on | Streams |
| --- | --- | --- |
| Android | Android 9 (API 28) or later: Android TV, Google TV, phones and tablets | MPEG-TS and HLS |
| Desktop | Linux, macOS and Windows, with [VLC](https://www.videolan.org/vlc/) 3 installed | MPEG-TS and HLS |
| iOS | iOS 16 or later | HLS |
| Web | Browsers with WebAssembly GC, or the JS build for others, served as [getting started](docs/getting-started.md#web) describes | HLS where the browser plays it |

Every platform needs a Sofa King server it can reach over the network.

## Quick start

With a Sofa King server on your network, download the APK from the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot) and install it on the TV over `adb`:

```sh
adb connect TV_IP:DEBUG_PORT
adb install -r sofa-king-snapshot.apk
```

Open Sofa King, select Settings, enter the server's address, for example `http://SERVER_HOST:9193`, and its API token, then select Save & reload. [Getting started](docs/getting-started.md) covers pairing the TV, the desktop, web and iOS apps, and checking the server first.

To build from source, see [development](docs/development.md#build-and-test).

## Documentation

The [documentation index](docs/README.md) lists every page. The most used are:

- [Getting started](docs/getting-started.md)
- [Using Sofa King](docs/usage.md)
- [Troubleshooting and FAQ](docs/troubleshooting.md)
- [Architecture](docs/architecture.md)
- [Security](docs/security.md)

## Contributing

[Development](docs/development.md) has the build and test commands and the checks on a TV. The repository's [CONTRIBUTING.md](../CONTRIBUTING.md) covers the commit and pull request conventions and CI.

## Security

The [security page](docs/security.md) describes what the app stores, where the token is sent, and cleartext HTTP. Report a vulnerability as the repository's [SECURITY.md](../SECURITY.md) describes.

## Limitations

- The app does not discover the server. Enter its address by hand.
- iOS and the web play HLS channels only; MPEG-TS needs Android or a desktop.
- The desktop app needs VLC 3. The `.deb` installs it through apt; on macOS and Windows install it first.
- The web app has no default server and needs an origin the server allows, as [getting started](docs/getting-started.md#web) describes.
- Caption timing and smoothing work on Android only. Chrome's built-in HLS player shows no captions.
- There is no recording and no background playback. Playback stops when the app leaves the screen.
- There is no published iOS build. Snapshot APKs use a debug key and the desktop packages are unsigned.
- The repository is private. Snapshot downloads need access to it.

## License

Licensed under the [GNU General Public License v3.0](../LICENSE).
