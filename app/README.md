# Sofa King app

The Sofa King app is the front of a home media center: live TV with a program guide, recordings and your own movies and shows, all from your own [Sofa King server](../backend/README.md). It is built for Android TV and Google TV, and the same app runs on Android phones, Linux, macOS and Windows desktops, iOS and the web. Channels, the guide, recordings, the libraries and the streams come from that server; the app includes no subscription.

One remote, Sofa King simple: **Live** for the guide and the channels, **Library** for the movies and shows, and **DVR** for what the server recorded, each of the last two shown when the server keeps it, and **Settings** for the server, playback and the device.

## What it does

- **Made for a remote.** Every screen works with the five-way D-pad, Back, and the Guide, Menu, Search, channel and digit keys. A keyboard, touch or a mouse work too.
- **A program guide** covering 24 hours, a day at a time up to a week ahead, with category, favorite and recent filters, program details, search and voice search ([guide](docs/usage.md#guide)).
- **Previews that become the show.** The focused channel plays muted and opens full screen on the same stream, with no second tune ([preview](docs/usage.md#preview)).
- **A full-screen player** with channel up and down, number entry, the previous channel, a channel list and a stream card with audio tracks and stream details.
- **Back where you left off.** A cold start opens the last channel, or the Library or DVR tab if you left from there, and a saved lineup shows at once while the app refreshes.
- **Recordings.** Record a program or a series from its details; the DVR tab plays recordings as they grow, resumes them and can skip the commercial breaks the server found ([DVR](docs/usage.md#dvr)).
- **Your own library.** Search, Continue watching, poster grids, item pages, a seekable player with audio and subtitle choices, Skip intro and Up next ([library](docs/usage.md#library)), and music with radio, synced lyrics and a visualizer ([music](docs/usage.md#music)).
- **Live captions.** CEA-608 and CEA-708 captions, with timing you can set per channel on Android ([captions](docs/usage.md#captions)).
- **Light on the server.** One playback session at a time; a failed stream reconnects with backoff.

## Platforms

| Platform | Runs on | Streams |
| --- | --- | --- |
| Android | Android 9 (API 28) or later: Android TV, Google TV, phones and tablets | MPEG-TS and HLS |
| Desktop | Linux, macOS and Windows, with [VLC](https://www.videolan.org/vlc/) 3 installed | MPEG-TS and HLS |
| iOS | iOS 16 or later, built from source | HLS |
| Apple TV | tvOS 17 or later, as a SwiftUI app on the same data layer, built from source | HLS |
| Web | Browsers with WebAssembly GC, or the JS build for others, served as [getting started](docs/getting-started.md#web) describes | HLS where the browser plays it |

Every platform needs a Sofa King server it can reach over the network.

## Quick start

With a Sofa King server on your network, install the APK from the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot) on the TV: in the Downloader app from Google Play, open `https://github.com/jamesarich/sofa-king/releases/download/snapshot/sofa-king-snapshot.apk` and choose Install. To install from a computer over `adb`, see [getting started](docs/getting-started.md#android-tv-and-google-tv).

Open Sofa King, select Settings, enter the server's address, for example `SERVER_HOST` (the app fills in `http://` and port 9193), and its API token, then select Save & reload. [Getting started](docs/getting-started.md) covers pairing the TV, the desktop, web and iOS apps, and checking the server first.

To build from source, see [development](docs/development.md#build-and-test).

## Documentation

The [documentation index](docs/README.md) lists every page. [CONTRIBUTING.md](../CONTRIBUTING.md) covers conventions and CI, and [SECURITY.md](../SECURITY.md) how to report a vulnerability.

## Limitations

- The web app does not discover the server; enter its address by hand. Android, Android TV, desktops, iPhone, iPad and Apple TV list the servers on their network.
- iOS, Apple TV and the web play MPEG-TS channels as the HLS the server packages, so their first picture takes a few seconds longer; Android and desktops play MPEG-TS directly. Library files and recordings play on every platform, since the server converts what a player cannot take.
- Caption timing and smoothing work on Android only. Chrome's built-in HLS player shows no captions.
- Only music plays in the background ([music](docs/usage.md#music)). On an Android phone or tablet a video player shrinks into picture-in-picture when the app leaves the screen; elsewhere video stops, and a library item or recording resumes where it stopped.
- Snapshot APKs use the release key only once the repository has it ([signing](docs/development.md#signing)), and the desktop packages are unsigned.

## License

Licensed under the [GNU General Public License v3.0](../LICENSE).
