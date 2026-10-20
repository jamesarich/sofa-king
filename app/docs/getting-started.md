# Getting started

This tutorial takes a Sofa King server with channels to a channel playing in the app. It installs the app on an Android TV, a desktop or a browser, connects it to the server and opens a channel.

Replace `SERVER_HOST` with the server's LAN address. The server answers on port 9193 when it runs in Docker.

## What you need

- A [Sofa King server](../../backend/README.md) on your network with a lineup. Its [getting started](../../backend/docs/getting-started.md) goes from an empty folder to a lineup.
- The server's API token, or a pairing code from the admin portal's [Devices page](../../backend/docs/admin.md#devices), which stands in for the token for ten minutes. A token the server generated is `apiToken` in its `secrets.json`; a token set with `SOFA_API_TOKEN` is not stored there. The admin portal's [Settings page](../../backend/docs/admin.md#settings) reveals the API token under Advanced either way. A server bound to loopback may run without one.
- One of the devices in the [platforms table](../README.md#platforms).

## 1. Check the server

From any machine on the network, check that the server answers and lists channels:

```sh
curl --fail http://SERVER_HOST:9193/readyz
curl --fail -H "Authorization: Bearer $SOFA_API_TOKEN" http://SERVER_HOST:9193/api/v1/channels
```

The first answers `ready`. The second lists the lineup as JSON. A `401` means the token is wrong, and an empty list means the server has no lineup yet; the server's troubleshooting covers both.

## 2. Install the app

The [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot) has a build of every platform but iOS and Apple TV from the latest `main`. To build from source instead, see [run each platform](development.md#run-each-platform).

### Android TV and Google TV

Download `sofa-king-snapshot.apk`. On the TV, open Settings → System → About and press Android TV OS build seven times to enable developer options. Turn on Wireless debugging, then choose Pair device with pairing code.

Replace the addresses and code below with the values the TV shows. Pairing and debugging use different ports:

```sh
adb pair TV_IP:PAIRING_PORT PAIRING_CODE
adb connect TV_IP:DEBUG_PORT
adb install -r sofa-king-snapshot.apk
```

Sofa King appears with the TV's apps. Phones and tablets take the same APK. Later snapshots install from Settings → About → Install update, with no adb, as [the update check](usage.md#update-check) describes.

### Desktop

The desktop app plays streams through [VLC](https://www.videolan.org/vlc/) 3. On Linux, install the package with apt, which installs VLC with it:

```sh
sudo apt install ./sofa-king-snapshot-linux-amd64.deb
```

On macOS and Windows, install VLC 3 first, then `sofa-king-snapshot-macos-arm64.dmg` or `sofa-king-snapshot-windows-x64.msi`. Those packages are unsigned, so macOS and Windows ask before the first start.

### Web

The web app is a set of static files: unzip `sofa-king-snapshot-web-wasm.zip`, or `sofa-king-snapshot-web-js.zip`, the same app for browsers without WebAssembly GC. A browser lets the page call the Sofa King server only from an origin the server allows, so serve the files one of two ways:

- Through a reverse proxy that serves the page and forwards `/api/`, `/media/` and `/logos/` at its origin root to the Sofa King server, so the page and the server share an origin. The server's [deploy guide](../../backend/docs/deploy.md#serve-the-web-app) has Caddy and nginx configurations.
- From any static web server, with that server's origin, such as `http://192.168.1.20:8080`, in the Sofa King server's `SOFA_CORS_ORIGINS`.

The web app has no default server. Open the page and enter the address in Settings: the proxy's origin with no path, such as `https://tv.example`, behind a proxy, or the Sofa King server's own address otherwise. The browser saves it in localStorage for the page's origin, so each origin that serves the app keeps its own settings.

A sub-path does not work. The server's stream and logo URLs start at `/media/` and `/logos/`, and the app resolves them against the origin of the address you enter, so the proxy must forward those paths at its origin root and the address takes no path.

A page served over HTTPS cannot call a plain-HTTP server, so serve both through the same HTTPS proxy. The web app plays HLS channels only.

### iOS and Apple TV

There is no published iOS or Apple TV build. On a Mac, build them from source as [run each platform](development.md#run-each-platform) describes. Both play HLS channels only.

## 3. Connect to the server

The first start opens on Connect to your Sofa King server with the header's Settings button focused. Press OK, then under Server:

1. Enter the Server URL: the server's address, for example `SERVER_HOST`, which the app completes as `http://SERVER_HOST:9193`, or the whole URL when the server uses another port or HTTPS. For the web app behind a proxy, enter the proxy's origin, as [web](#web) describes.
2. Enter the API token, or leave it empty when the server runs without one. The field shows only the last four characters once you leave it.
3. Select Save & reload.

Or pair instead of typing the token: in the admin portal, open Players, then Devices, and choose Show a pairing code, then on the TV enter the Server URL and the six digits under Pairing code and select Pair. The app stores the token the server hands out. On an Android phone, an iPhone or an iPad with the app installed, scanning the QR code on that page with the camera pairs at once.

The guide opens and fills in: channels first, then their programs.

## 4. Watch a channel

Move through the guide with the D-pad or the arrow keys. The focused channel plays in a muted preview; its sound comes on after 5 seconds and it opens full screen after 10. Press OK to open it at once.

In the player, Up and Down change channels in the direction they move the guide and Back returns to the guide. The next start opens the channel you last watched.

When the server keeps [media libraries](../../backend/docs/libraries.md), a Library tab sits beside Live, and a server that records adds a Recordings tab; program details then offer Record.

[Using Sofa King](usage.md) has every control, the library, recordings, captions and the settings. [Troubleshooting](troubleshooting.md) covers a guide that stays empty or a channel that does not play.
