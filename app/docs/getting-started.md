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

The TV can download the app itself, with no computer:

1. On the TV, install **Downloader** by AFTVnews from Google Play.
2. Allow it to install apps: Settings → Apps → Security & restrictions → Unknown sources, then turn on Downloader. Some TVs keep the switch under Settings → Privacy or Device Preferences → Security instead.
3. Open Downloader, type `https://github.com/jamesarich/sofa-king/releases/download/snapshot/sofa-king-snapshot.apk` in its address box and choose Go. When the download finishes, choose Install.

To install from a computer over adb instead, download `sofa-king-snapshot.apk`. On the TV, open Settings → System → About and press Android TV OS build seven times to enable developer options. Turn on Wireless debugging, then choose Pair device with pairing code.

Replace the addresses and code below with the values the TV shows. Pairing and debugging use different ports:

```sh
adb pair TV_IP:PAIRING_PORT PAIRING_CODE
adb connect TV_IP:DEBUG_PORT
adb install -r sofa-king-snapshot.apk
```

Sofa King appears with the TV's apps. Phones and tablets take the same APK: open the address above in the phone's browser and allow the browser to install it when Android asks. To have [Obtainium](https://obtainium.imranr.dev/) keep a phone or tablet up to date instead, add `https://github.com/jamesarich/sofa-king` as an app with Include prereleases turned on, since the snapshot is a prerelease. Later snapshots install from Settings → About → Install update, with no adb, as [the update check](usage.md#update-check) describes.

### Desktop

The desktop app plays streams through [VLC](https://www.videolan.org/vlc/) 3. On Debian or Ubuntu, add the snapshot release as an apt source and install the package, which installs VLC with it; later snapshots then arrive with the system's other updates, through `sudo apt upgrade` or Software Updater:

```sh
sudo curl -fsSLo /usr/share/keyrings/sofa-king.gpg https://github.com/jamesarich/sofa-king/releases/download/snapshot/sofa-king-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/sofa-king.gpg] https://github.com/jamesarich/sofa-king/releases/download/snapshot/ ./" | sudo tee /etc/apt/sources.list.d/sofa-king.list
sudo apt update && sudo apt install sofa-king
```

To install one snapshot without the source, download `sofa-king-snapshot-linux-amd64.deb` and run `sudo apt install ./sofa-king-snapshot-linux-amd64.deb`. A package from before the 0.1.0 versions, which reads 1.1.0, needs `--allow-downgrades` once.

On macOS and Windows, install VLC 3 first, then `sofa-king-snapshot-macos-arm64.dmg` or `sofa-king-snapshot-windows-x64.msi`. Those packages are unsigned, so macOS and Windows ask before the first start.

### Web

The Sofa King server serves the web app: open `http://SERVER_HOST:9193/app/` in a browser, or the server's address alone. Settings fills in the page's origin as the server address, so pair as [connect to the server](#3-connect-to-the-server) describes, or choose Open the web app on the admin portal's [Devices page](../../backend/docs/admin.md#devices), which opens it already paired. This copy comes with the server and updates when it does, so Settings checks GitHub for no newer build.

To host the files elsewhere (`sofa-king-snapshot-web-wasm.zip`, or `sofa-king-snapshot-web-js.zip` for browsers without WebAssembly GC), see [serve the web app](../../backend/docs/deploy.md#serve-the-web-app), which covers the reverse proxy, CORS and HTTPS. The browser saves the address in localStorage for the page's origin, so each origin keeps its own settings. The web app plays HLS channels only.

### iOS and Apple TV

There is no published iOS or Apple TV build. On a Mac, build them from source as [run each platform](development.md#run-each-platform) describes. Both play HLS channels only.

## 3. Connect to the server

The first start opens on Connect to your Sofa King server with the header's Settings button focused. Press OK to open Settings, then pair the app with the server. In the admin portal, open [Devices](../../backend/docs/admin.md#devices) and choose Show a pairing code.

- On a TV, enter the Server address (for example `SERVER_HOST`, which the app completes as `http://SERVER_HOST:9193`, or the whole URL when the server uses another port or HTTPS) and the six digits under Pairing code, then select Pair. On the same network as the server, Servers on this network lists it above the field; selecting it fills the address in (Android, Android TV, iPhone and iPad, desktops and Apple TV).
- On an Android phone, an iPhone or an iPad with the app installed, scan the QR code on that page with the camera.
- The web app fills in its page's own origin, which is right behind a proxy, as [web](#web) describes.

The app stores the token the server hands out. If the app already uses another server, it asks before switching ([links](usage.md#links)).

To type the API token instead, enter the Server address as above and the API token (leave it empty when the server runs without one), then select Save & reload.

The guide opens and fills in: channels first, then their programs.

## 4. Watch a channel

Move through the guide with the D-pad or the arrow keys. The focused channel plays in a muted preview and opens full screen after a pause ([timing](usage.md#preview)). Press OK to open it at once.

In the player, Up and Down change channels in the direction they move the guide and Back returns to the guide. The next start opens the channel you last watched.

[Using Sofa King](usage.md) has every control, the library, recordings, captions and the settings. [Troubleshooting](troubleshooting.md) covers a guide that stays empty or a channel that does not play.
