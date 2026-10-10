# Sofa King

An Android TV and Google TV app for watching IPTV channels from Dispatcharr. It has a program guide, muted channel previews, favorites, and a full-screen player with closed captions.

Requires Android 9 or later and a TV remote. Channels and guide data come from your server; the app does not include a channel subscription.

## Set up your server

1. Create a channel profile in [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr).
2. Add channels to that profile and assign their guide data.
3. In Sofa King's Settings, enter the server's HTTP(S) base address and profile name.
4. Return to the guide and select Refresh.

The TV must be able to reach the server. Compatible servers must provide `/output/m3u/PROFILE` and `/output/epg/PROFILE?days=1`. The separate [Sofa King Backend](https://github.com/jamesarich/sofa-king-backend) exposes a JSON API that this app does not currently consume.

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

The release APK is optimized with R8 and signed with the local debug key.

To set server defaults at build time, replace the host, port and profile:

```sh
./gradlew :app:assembleRelease \
  -PdispatcharrUrl=http://SERVER_HOST:SERVER_PORT \
  -PdispatcharrProfile=PROFILE
```

Settings can override both values on the TV.

## Use

In the guide, move with the D-pad, press OK to watch, and long-press OK to add or remove a favorite. In the player, Up/Down change channels, OK opens the info bar, and Back returns to the guide.

See [controls and troubleshooting](docs/USAGE.md) for the full remote controls and caption settings.

## Development

- [Build and test](docs/DEVELOPMENT.md)
- [Architecture](docs/ARCHITECTURE.md)

## License

[GNU General Public License v3.0](LICENSE).
