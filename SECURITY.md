# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub's private vulnerability reporting: open the [Security tab](https://github.com/jamesarich/sofa-king/security/advisories/new) and choose Report a vulnerability. Do not open a public issue for a vulnerability.

## What the app stores

The server address and API token are saved in DataStore, in the app's private storage. They are not encrypted at rest. App backup and device-to-device transfer exclude them (`allowBackup` is off and `data_extraction_rules.xml` excludes all data).

The token is sent only in the `Authorization` header of requests to the server's `/api/v1` API. Media requests under `/media` carry no token. The app does not log the token.

## Cleartext HTTP

The app allows cleartext HTTP (`network_security_config.xml`) so it can reach a Sofa King server on your LAN, which serves plain HTTP. Traffic to that server, including the token and the streams, is readable by anyone who can observe the network path.

If the server is reachable beyond your LAN, serve it over HTTPS and enter its `https://` address in Settings. Set `SOFA_API_TOKEN` on the server in either case.
