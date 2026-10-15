# Security

This page covers what the app stores, where it sends the API token, and its cleartext HTTP. To report a vulnerability, follow the [repository's security policy](../../SECURITY.md#reporting-a-vulnerability). The [server's security page](../../backend/docs/security.md) covers its tokens, routes and limits.

## What the app stores

The server address and API token are saved with the other settings: in DataStore in the app's private storage on Android, desktops and iOS, in `NSUserDefaults` on Apple TV, and in the browser's localStorage for the web app's origin. They are not encrypted at rest. [Storage](architecture.md#storage) lists the files on each platform. On Android, app backup and device-to-device transfer exclude them (`allowBackup` is off and `data_extraction_rules.xml` excludes all data).

The token is sent only in the `Authorization` header of requests to the server's `/api/v1` API. Media requests under `/media` carry no token. The app does not log the token, and Settings shows only its last four characters while the field is not being edited.

## Cleartext HTTP

The app allows cleartext HTTP (`network_security_config.xml` on Android, `NSAllowsArbitraryLoads` on iOS and Apple TV) so it can reach a Sofa King server on your LAN, which serves plain HTTP. A web app served over HTTPS cannot call a plain-HTTP server; browsers block the mixed content. Traffic to that server, including the token and the streams, is readable by anyone who can observe the network path.

If the server is reachable beyond your LAN, serve it over HTTPS and enter its `https://` address in Settings. Set `SOFA_API_TOKEN` on the server in either case.
