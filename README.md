# Sofa King

An Android TV and Google TV app for watching live channels from a [Sofa King server](https://github.com/jamesarich/sofa-king-backend). It has a program guide, muted channel previews, favorites, and a full-screen player with closed captions.

Requires Android 9 or later and a TV remote. Channels and guide data come from your server; the app does not include a channel subscription.

## Set up your server

1. Run the [Sofa King server](https://github.com/jamesarich/sofa-king-backend) on your network with your providers configured. Set `SOFA_API_TOKEN` when the server is reachable beyond localhost.
2. In Sofa King's Settings, enter the server's HTTP(S) address with its port, for example `http://192.168.1.3:9193`, and the API token.
3. Select Save & reload.

The TV must be able to reach the server. The app uses the server's `/api/v1` JSON API for channels, guide data and playback sessions, and plays media from its `/media` relay.

## Build and install

Requires JDK 21, the Android SDK with API 37.1, and `adb` on PATH. Set `ANDROID_HOME` to your SDK directory and run from the repository root:

```sh
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
```

Enable developer options on the TV: open Settings → System → About and press Android TV OS build seven times. Enable Wireless debugging, then choose Pair device with pairing code.

Replace the addresses and code shown below with the values displayed on the TV. Pairing and debugging use different ports:

```sh
adb pair TV_IP:PAIRING_PORT PAIRING_CODE
adb connect TV_IP:DEBUG_PORT
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release APK is optimized with R8 and signed with the local debug key. The server address and token are entered on the TV; the app ships without defaults.

## Use

The app opens on the channel you last watched; Back shows the guide. In the guide, move with the D-pad, press OK to watch, long-press OK to add or remove a favorite, and press Back to go up to the filter chips (Back again exits). In the player, Up/Down change channels, OK opens the info bar, Back returns to the guide, and Guide (or Menu) opens it at the current time.

See [controls and troubleshooting](docs/USAGE.md) for the full remote controls and caption settings.

## Development

- [Build and test](docs/DEVELOPMENT.md)
- [Architecture](docs/ARCHITECTURE.md)

## License

[GNU General Public License v3.0](LICENSE).
