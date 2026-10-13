# Sofa King

Live TV, Sofa King good.

Sofa King is an app for watching live channels from your own [Sofa King server](https://github.com/jamesarich/sofa-king-backend) on Android TV and Google TV, with the same app on Android phones, Linux, macOS and Windows desktops, iOS and the web. Channels and guide data come from that server; the app includes no channel subscription.

## Features

- A program guide covering 24 hours, with channel filters, search and voice search
- Channel previews that play muted, unmute after 5 seconds and open full screen after 10 (or 30, or never), continuing the same stream
- Favorites, and the last watched channel restored on launch
- A full-screen player with channel up and down, number entry and a channel list
- Closed captions for MPEG-TS and HLS streams, with per-channel timing and style settings

## Platforms

| Platform | Runs on | Streams |
| --- | --- | --- |
| Android | Android 9 (API 28) or later: Android TV, Google TV, phones and tablets | MPEG-TS and HLS |
| Desktop | Linux, macOS and Windows, with [VLC](https://www.videolan.org/vlc/) 3 installed | MPEG-TS and HLS |
| iOS | iOS 16 or later | HLS |
| Web | Browsers with WebAssembly GC, served from an origin the server allows | HLS where the browser plays it |

Every platform needs a Sofa King server it can reach over the network. A TV remote, a keyboard, touch or a mouse all work.

Building from source needs JDK 21 and the Android SDK with API 37.1; `adb` on your PATH to sideload; a Mac with Xcode and XcodeGen for iOS.

## Install

### Snapshot builds

Each push to `main` that passes CI replaces the [`snapshot` prerelease](../../releases/tag/snapshot) with a release APK, `.deb`, `.dmg` and `.msi` desktop packages and zipped web builds. They are test builds: the APK uses a debug key and the desktop packages are unsigned.

### Build from source

Set `ANDROID_HOME` to your SDK directory, then run this from the repository root:

```sh
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :androidApp:assembleRelease
```

The release APK is minified with R8 and signed with the local debug key, so it is for testing. Distributing it requires a release signing configuration. To build the desktop, web and iOS apps, see [run each platform](docs/DEVELOPMENT.md#run-each-platform).

### Sideload with adb

On the TV, open Settings → System → About and press Android TV OS build seven times to enable developer options. Turn on Wireless debugging, then choose Pair device with pairing code.

Replace the addresses and code below with the values shown on the TV. Pairing and debugging use different ports:

```sh
adb pair TV_IP:PAIRING_PORT PAIRING_CODE
adb connect TV_IP:DEBUG_PORT
adb install -r androidApp/build/outputs/apk/release/androidApp-release.apk
```

## Set up

1. Run the [Sofa King server](https://github.com/jamesarich/sofa-king-backend) on your network with your providers configured. Set `SOFA_API_TOKEN` on the server when it is reachable beyond localhost.
2. In Sofa King, open Settings and enter the server's HTTP(S) address with its port, for example `http://server.example:9193`, and the API token. Leave the token empty if the server runs without one.
3. Select Save & reload.

The server is not discovered automatically; enter its address and token by hand. The API token is the server's `SOFA_API_TOKEN`.

## Using it

The app opens on the channel you last watched. Back shows the guide. In the guide, move with the D-pad, press OK to watch, and long-press OK to add or remove a favorite. Press Back to move up to the filter chips, and Back again to exit. In the player, Up and Down change channels, OK opens the info bar, Back returns to the guide, Guide (or Menu) opens the guide at the current time, and Search opens voice search.

On a computer, the arrow keys, Enter and Escape stand in for the D-pad, OK and Back. See [controls and troubleshooting](docs/USAGE.md) for the full remote controls and caption settings.

## Development

- [Build and test](docs/DEVELOPMENT.md)
- [Architecture](docs/ARCHITECTURE.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

See [SECURITY.md](SECURITY.md) for how to report a vulnerability and what the app stores.

## License

Sofa King is licensed under the [GNU General Public License v3.0](LICENSE).
