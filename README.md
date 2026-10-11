# Sofa King

Live TV, Sofa King good.

Sofa King is an Android TV and Google TV app for watching live channels from your own [Sofa King server](https://github.com/jamesarich/sofa-king-backend). Channels and guide data come from that server; the app includes no channel subscription.

## Features

- A program guide covering 24 hours, with channel filters, search and voice search
- Channel previews that play muted, unmute after 5 seconds and open full screen after 10, continuing the same stream
- Favorites, and the last watched channel restored on launch
- A full-screen player with channel up and down, number entry and a channel list
- Closed captions for MPEG-TS and HLS streams, with per-channel timing and style settings

## Requirements

- Android 9 (API 28) or later, on Android TV or Google TV
- A TV remote
- A Sofa King server the TV can reach over the network

Building from source needs JDK 21, the Android SDK with API 37.1, and `adb` on your PATH to sideload.

## Install

### Build from source

Set `ANDROID_HOME` to your SDK directory, then run this from the repository root:

```sh
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
```

The release APK is minified with R8 and signed with the local debug key, so it is for testing. Distributing it requires a release signing configuration.

### Sideload with adb

On the TV, open Settings → System → About and press Android TV OS build seven times to enable developer options. Turn on Wireless debugging, then choose Pair device with pairing code.

Replace the addresses and code below with the values shown on the TV. Pairing and debugging use different ports:

```sh
adb pair TV_IP:PAIRING_PORT PAIRING_CODE
adb connect TV_IP:DEBUG_PORT
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Set up

1. Run the [Sofa King server](https://github.com/jamesarich/sofa-king-backend) on your network with your providers configured. Set `SOFA_API_TOKEN` on the server when it is reachable beyond localhost.
2. On the TV, open Sofa King's Settings and enter the server's HTTP(S) address with its port, for example `http://server.example:9193`, and the API token. Leave the token empty if the server runs without one.
3. Select Save & reload.

The server is not discovered automatically; enter its address and token by hand. The API token is the server's `SOFA_API_TOKEN`.

## Using it

The app opens on the channel you last watched. Back shows the guide. In the guide, move with the D-pad, press OK to watch, and long-press OK to add or remove a favorite. Press Back to move up to the filter chips, and Back again to exit. In the player, Up and Down change channels, OK opens the info bar, Back returns to the guide, Guide (or Menu) opens the guide at the current time, and Search opens voice search.

See [controls and troubleshooting](docs/USAGE.md) for the full remote controls and caption settings.

## Development

- [Build and test](docs/DEVELOPMENT.md)
- [Architecture](docs/ARCHITECTURE.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

See [SECURITY.md](SECURITY.md) for how to report a vulnerability and what the app stores.

## License

Sofa King is licensed under the [GNU General Public License v3.0](LICENSE).
