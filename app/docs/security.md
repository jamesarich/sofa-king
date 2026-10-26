# Security

This page covers what the app stores, where it sends the API token, and its cleartext HTTP. To report a vulnerability, follow the [repository's security policy](../../SECURITY.md#reporting-a-vulnerability). The [server's security page](../../backend/docs/security.md) covers its tokens, routes and limits.

The app collects no telemetry, analytics or crash reports. It talks only to the server you set and, for the update check, to GitHub, as the next sections describe.

## What the app stores

The server address and API token are saved with the other settings: in DataStore in the app's private storage on Android, desktops and iOS, in `NSUserDefaults` on Apple TV, and in the browser's localStorage for the web app's origin. They are not encrypted at rest. A Settings PIN is stored beside them as a salted SHA-256 hash, never the digits, and is never sent to the server. [Storage](architecture.md#storage) lists the files on each platform. On Android, app backup and device-to-device transfer exclude them (`allowBackup` is off and `data_extraction_rules.xml` excludes all data).

The token is sent only in the `Authorization` header of requests to the server's `/api/v1` API. Pairing with a code sends the code, the app's client id, platform and device name to `/api/v1/pair` without a token, and the server answers with a token of this device's own, which the app then saves like one typed in. With a paired or configured server, the app also keeps its hidden channels, favorites and recent channels on the server under that client id, so the server's admin can see and forget them; forgetting a paired device also revokes its token, and the app then needs pairing again. Media requests under `/media`, including library and recording playback under `/media/vod`, carry no token, and nor do logos and library artwork under `/logos` and `/artwork`, which image loaders fetch without one. The update check in Settings asks GitHub's public releases API for the `snapshot` release, with no token or client id. On Android, Install update downloads the release's APK from GitHub and installs it only after its SHA-256 matches the digest GitHub records for the asset, its package name is the app's, its signing certificates are the installed app's and its version code is higher; Android's installer enforces the key and version again. The app holds `REQUEST_INSTALL_PACKAGES` for this, which Android grants only after the viewer allows it to install apps, and `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, which lets an update the viewer asked for skip the second prompt. The app does not log the token, and Settings shows only its last four characters while the field is not being edited.

## Local network

The server is on the local network, which Android 17 opens to an app only with the `ACCESS_LOCAL_NETWORK` permission, part of Nearby devices. The Android app declares it and asks for it at its first start on Android 17; denied, it cannot reach or find the server until the permission is allowed in the system's app settings. Apple TV and iOS ask for local network access the first time the app reaches the server. Settings browses for servers over mDNS (`_sofaking._tcp`) only while its server fields are open.

The Android app declares `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` for `MusicService`, which runs only while a song is loaded. The service is exported, as Media3 requires, so that the system's media controls and other controllers can bind to it; they reach only the music's session, which plays, pauses and steps through the queue already playing.

## Cleartext HTTP

The app allows cleartext HTTP (`network_security_config.xml` on Android, `NSAllowsArbitraryLoads` on iOS and Apple TV) so it can reach a Sofa King server on your LAN, which serves plain HTTP. A web app served over HTTPS cannot call a plain-HTTP server; browsers block the mixed content. Traffic to that server, including the token and the streams, is readable by anyone who can observe the network path.

If the server is reachable beyond your LAN, serve it over HTTPS and enter its `https://` address in Settings. Set `SOFA_API_TOKEN` on the server in either case.

## Verify a download

Every package on the [releases](https://github.com/jamesarich/sofa-king/releases), the APK, the `.deb`, the DMG, the MSI and the web archives, carries a GitHub build attestation that ties the file to the commit and the workflow run that built it. With the [GitHub CLI](https://cli.github.com/), check one before installing it:

```sh
gh attestation verify sofa-king-snapshot.apk --repo jamesarich/sofa-king
```

A file that someone else built or changed fails the check. The attestation proves where a file came from, not that it is signed for its platform: the desktop packages are still unsigned, so macOS and Windows ask before the first start.
