# Sofa King

Android TV / Google TV live-TV client for a self-hosted [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) server.

- Guide grid with category and favorites filters; long-press a programme to star its channel.
- Full-screen player: Up/Down or Channel keys zap, digits jump to a channel number, Previous/Last returns to the last channel, OK/Left/Right toggle the info overlay, Back returns to the guide.
- Lineup and guide reload every 3 hours, or from Refresh.
- Settings: Dispatcharr URL and channel profile, stored on the device.

## Build and install

```
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Default server and profile come from `-PdispatcharrUrl=` and `-PdispatcharrProfile=` (defaults `http://192.168.1.3:9191`, `livingroom`).

On a Google TV: Settings > System > About, press the Android TV OS build seven times, then Developer options > Wireless debugging > Pair device with pairing code, `adb pair <ip:port> <code>`, `adb connect <ip:port>`.
