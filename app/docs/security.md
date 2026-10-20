# Security

This page covers what the app stores, where it sends the API token, and its cleartext HTTP. To report a vulnerability, follow the [repository's security policy](../../SECURITY.md#reporting-a-vulnerability). The [server's security page](../../backend/docs/security.md) covers its tokens, routes and limits.

## What the app stores

The server address and API token are saved with the other settings: in DataStore in the app's private storage on Android, desktops and iOS, in `NSUserDefaults` on Apple TV, and in the browser's localStorage for the web app's origin. They are not encrypted at rest. A Settings PIN is stored beside them as a salted SHA-256 hash, never the digits, and is never sent to the server. [Storage](architecture.md#storage) lists the files on each platform. On Android, app backup and device-to-device transfer exclude them (`allowBackup` is off and `data_extraction_rules.xml` excludes all data).

The token is sent only in the `Authorization` header of requests to the server's `/api/v1` API. Pairing with a code sends the code, the app's client id, platform and device name to `/api/v1/pair` without a token, and the server answers with the API token, which the app then saves like one typed in. With a paired or configured server, the app also keeps its hidden channels, favorites and recent channels on the server under that client id, so the server's admin can see and forget them. Media requests under `/media`, including library and recording playback under `/media/vod`, carry no token, and nor do logos and library artwork under `/logos` and `/artwork`, which image loaders fetch without one. The update check in Settings asks GitHub's public releases API for the `snapshot` release, with no token or client id. The app does not log the token, and Settings shows only its last four characters while the field is not being edited.

## Local network

The server is on the local network, which Android 17 opens to an app only with the `ACCESS_LOCAL_NETWORK` permission, part of Nearby devices. The Android app declares it and asks for it at its first start on Android 17; denied, it cannot reach or find the server until the permission is allowed in the system's app settings. Apple TV and iOS ask for local network access the first time the app reaches the server. Settings browses for servers over mDNS (`_sofaking._tcp`) only while its server fields are open.

## Cleartext HTTP

The app allows cleartext HTTP (`network_security_config.xml` on Android, `NSAllowsArbitraryLoads` on iOS and Apple TV) so it can reach a Sofa King server on your LAN, which serves plain HTTP. A web app served over HTTPS cannot call a plain-HTTP server; browsers block the mixed content. Traffic to that server, including the token and the streams, is readable by anyone who can observe the network path.

If the server is reachable beyond your LAN, serve it over HTTPS and enter its `https://` address in Settings. Set `SOFA_API_TOKEN` on the server in either case.
