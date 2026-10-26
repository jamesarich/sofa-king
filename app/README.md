# Sofa King app

The Sofa King app is the front of a home media center: live TV with a program guide, recordings and your own movies and shows, all from your own [Sofa King server](../backend/README.md). It is built for Android TV and Google TV, and the same app runs on Android phones, Linux, macOS and Windows desktops, iOS and the web. Channels, the guide, recordings, the libraries and the streams come from that server; the app includes no subscription.

Three tabs, one remote, Sofa King simple: **Live** for the guide and the channels, **Library** for the movies and shows, and **DVR** for what the server recorded, each of the last two shown when the server keeps it.

## Why

- **Made for a remote.** Every screen works with the five-way D-pad, Back, and the Guide, Menu, Search, channel and digit keys. A keyboard, touch or a mouse work too.
- **Previews that become the show.** The focused channel plays muted in the guide, its sound comes on after 5 seconds, and it opens full screen after 10 on the same stream, with no second tune.
- **Everything else you watch, in the same app.** The Library tab resumes a movie or episode where you stopped and offers Up next, and the DVR tab plays a recording while it still records and can skip its commercial breaks. Every file plays on every platform, since the server converts what a player cannot take.
- **Back where you left off.** A cold start opens the last channel you watched, or the Library or DVR tab if you left from there, and Back from the player lands on that channel in the guide with your filter and scroll intact.
- **Light on the server.** The app holds one playback session at a time, renews it while you watch and releases it when you leave, so a small server's few slots go further.
- **Live captions that keep up.** CEA-608 and CEA-708 captions, with timing you can set per channel to line delayed live captions up with the speech.
- **One codebase.** Kotlin Multiplatform and Compose Multiplatform on every platform, with each platform's own player underneath.

## Features

- A program guide covering 24 hours, a day at a time up to a week ahead, with category, favorite and recent filters, program details, search and voice search
- Muted previews that open full screen after 10 seconds, 30 seconds or never ([using Sofa King](docs/usage.md#guide))
- Favorites, and a start where you left off: the last channel full screen, or the Library or DVR tab
- A full-screen player with channel up and down, number entry, the previous channel, a channel list with Recent, Favorites and filter chips, and a stream card with audio tracks and stream details
- Closed captions, with per-channel timing on Android and a style setting where the app draws them ([captions](docs/usage.md#captions))
- A saved lineup that shows at once while the app refreshes from the server
- Record a program or a series from its details, and a DVR tab that plays recordings as they grow, resumes them and can skip the commercial breaks the server found ([recordings](docs/usage.md#recordings))
- A Library tab with search, Continue watching, recent additions, poster grids and item pages for movies and shows, a seekable player with audio and subtitle choices, Skip intro, and Up next for the following episode ([library](docs/usage.md#library))
- Reconnection with backoff when a stream fails, and the channel followed by number, then name, when the server's lineup drops its id

## Platforms

| Platform | Runs on | Streams |
| --- | --- | --- |
| Android | Android 9 (API 28) or later: Android TV, Google TV, phones and tablets | MPEG-TS and HLS |
| Desktop | Linux, macOS and Windows, with [VLC](https://www.videolan.org/vlc/) 3 installed | MPEG-TS and HLS |
| iOS | iOS 16 or later | HLS |
| Apple TV | tvOS 17 or later, as a SwiftUI app on the same data layer, built from source | HLS |
| Web | Browsers with WebAssembly GC, or the JS build for others, served as [getting started](docs/getting-started.md#web) describes | HLS where the browser plays it |

Every platform needs a Sofa King server it can reach over the network.

## Quick start

With a Sofa King server on your network, install the APK from the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot) on the TV: in the Downloader app from Google Play, open `https://github.com/jamesarich/sofa-king/releases/download/snapshot/sofa-king-snapshot.apk` and choose Install. From a computer, `adb` installs it too:

```sh
adb connect TV_IP:DEBUG_PORT
adb install -r sofa-king-snapshot.apk
```

Open Sofa King, select Settings, enter the server's address, for example `SERVER_HOST` (the app fills in `http://` and port 9193), and its API token, then select Save & reload. [Getting started](docs/getting-started.md) covers pairing the TV, the desktop, web and iOS apps, and checking the server first.

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

- The web app does not discover the server; enter its address by hand. Android, Android TV, desktops, iPhone, iPad and Apple TV list the servers on their network.
- iOS, Apple TV and the web play HLS channels only; MPEG-TS needs Android or a desktop. Library files and recordings play on every platform, since the server converts what a player cannot take.
- The desktop app needs VLC 3. The `.deb` installs it through apt; on macOS and Windows install it first.
- The web app fills in its page's origin as the server address and needs an origin the server allows, as [getting started](docs/getting-started.md#web) describes.
- Caption timing and smoothing work on Android only. Chrome's built-in HLS player shows no captions.
- There is no background audio. On an Android phone or tablet a player shrinks into picture-in-picture when the app leaves the screen; elsewhere playback stops, and a library item or recording resumes where it stopped.
- There is no published iOS or Apple TV build. Snapshot APKs use the release key only once the repository has it ([signing](docs/development.md#signing)), and the desktop packages are unsigned.

## License

Licensed under the [GNU General Public License v3.0](../LICENSE).
