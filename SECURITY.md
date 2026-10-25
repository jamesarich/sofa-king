# Security

## Reporting a vulnerability

Do not open a public issue for a security problem. Report it privately through GitHub's private vulnerability reporting: open the [Security tab](https://github.com/jamesarich/sofa-king/security/advisories/new) and choose Report a vulnerability. Include the steps to reproduce and what they affect:

- For the app, its version from Settings → About and its platform.
- For the server, its commit or image revision and the route or code path involved.

Leave out live tokens, provider URLs with credentials and private addresses; a redacted example is enough.

## Supported versions

Fixes land on `main` and reach the snapshot channel first, then the next release. Until the first release is tagged, `main` is the only supported version.

| Version | Supported |
| --- | --- |
| The latest release (the newest `vX.Y.Z`, the image's `:latest`) | Yes |
| `main` (the `snapshot` prerelease, the image's `:main`) | Yes |
| Older releases | No; update to the latest |

## The server

The server's [security page](backend/docs/security.md) covers its intended deployment, which token opens which routes, the request limits, the outbound address policy and a hardening checklist.

## The app

The app's [security page](app/docs/security.md) covers what it stores, where it sends the API token, and its cleartext HTTP.
