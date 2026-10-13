# Getting started

This tutorial takes a Sofa King server with channels to a channel playing in the app. It installs the app on an Android TV, a desktop or a browser, connects it to the server and opens a channel.

Replace `SERVER_HOST` with the server's LAN address. The server answers on port 9193 when it runs in Docker.

## What you need

- A [Sofa King server](https://github.com/jamesarich/sofa-king-backend) on your network with a lineup. Its [getting started](https://github.com/jamesarich/sofa-king-backend/blob/main/docs/getting-started.md) goes from an empty folder to a lineup.
- The server's API token, `apiToken` in its `secrets.json`. A server bound to loopback may run without one.
- One of the devices in the [platforms table](../README.md#platforms).

## 1. Check the server

From any machine on the network, check that the server answers and lists channels:

```sh
curl --fail http://SERVER_HOST:9193/readyz
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:9193/api/v1/channels
```

The first answers `ready`. The second lists the lineup as JSON. A `401` means the token is wrong, and an empty list means the server has no lineup yet; the server's troubleshooting covers both.

## 2. Install the app

The [`snapshot` prerelease](../../../releases/tag/snapshot) has a build of every platform from the latest `main`. To build from source instead, see [run each platform](development.md#run-each-platform).

### Android TV and Google TV

Download `sofa-king-snapshot.apk`. On the TV, open Settings → System → About and press Android TV OS build seven times to enable developer options. Turn on Wireless debugging, then choose Pair device with pairing code.

Replace the addresses and code below with the values the TV shows. Pairing and debugging use different ports:

```sh
adb pair TV_IP:PAIRING_PORT PAIRING_CODE
adb connect TV_IP:DEBUG_PORT
adb install -r sofa-king-snapshot.apk
```

Sofa King appears with the TV's apps. Phones and tablets take the same APK.

### Desktop

Install [VLC](https://www.videolan.org/vlc/) 3, which plays the streams, then install the package for your system: `sofa-king-snapshot-linux-amd64.deb`, `sofa-king-snapshot-macos-arm64.dmg` or `sofa-king-snapshot-windows-x64.msi`. The packages are unsigned, so macOS and Windows ask before the first start.

### Web

The server sends no CORS headers, so the web app must be served from the server's origin. Unzip `sofa-king-snapshot-web-wasm.zip` and serve it from a reverse proxy that forwards `/api/` and `/media/` to the server, then open the proxy's address. `sofa-king-snapshot-web-js.zip` is the same app for browsers without WebAssembly GC. The web app plays HLS channels only.

### iOS

There is no published iOS build. On a Mac, build it from source as [run each platform](development.md#run-each-platform) describes. The iOS app plays HLS channels only.

## 3. Connect to the server

The first start opens on Connect to your Sofa King server with Settings focused. Press OK, then under Server:

1. Enter the Server URL with its port, for example `http://SERVER_HOST:9193`. For the web app, enter the proxy's address.
2. Enter the API token, or leave it empty when the server runs without one. The field shows only the last four characters once you leave it.
3. Select Save & reload.

The guide opens and fills in: channels first, then their programs.

## 4. Watch a channel

Move through the guide with the D-pad or the arrow keys. The focused channel plays in a muted preview; its sound comes on after 5 seconds and it opens full screen after 10. Press OK to open it at once.

In the player, Up and Down change channels and Back returns to the guide. The next start opens the channel you last watched.

[Using Sofa King](usage.md) has every control, captions and the settings. [Troubleshooting](troubleshooting.md) covers a guide that stays empty or a channel that does not play.
