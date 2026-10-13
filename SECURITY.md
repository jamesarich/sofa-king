# Security

## Reporting a vulnerability

Do not open a public issue for a security problem. Report it privately through GitHub's private vulnerability reporting: open the [Security tab](https://github.com/jamesarich/sofa-king/security/advisories/new) and choose Report a vulnerability. Include the version from Settings → About or the commit you built, the platform, and the steps to reproduce. Leave out live tokens and private addresses; a redacted example is enough.

A problem in the server, its API or its streams belongs to the [server's security policy](backend/SECURITY.md).

## What the app stores

The server address and API token are saved with the other settings: in DataStore in the app's private storage on Android, desktops and iOS, and in the browser's localStorage for the web app's origin. They are not encrypted at rest. On Android, app backup and device-to-device transfer exclude them (`allowBackup` is off and `data_extraction_rules.xml` excludes all data).

The token is sent only in the `Authorization` header of requests to the server's `/api/v1` API. Media requests under `/media` carry no token. The app does not log the token.

## Cleartext HTTP

The app allows cleartext HTTP (`network_security_config.xml` on Android, `NSAllowsArbitraryLoads` on iOS) so it can reach a Sofa King server on your LAN, which serves plain HTTP. A web app served over HTTPS cannot call a plain-HTTP server; browsers block the mixed content. Traffic to that server, including the token and the streams, is readable by anyone who can observe the network path.

If the server is reachable beyond your LAN, serve it over HTTPS and enter its `https://` address in Settings. Set `SOFA_API_TOKEN` on the server in either case.
