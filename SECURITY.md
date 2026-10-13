# Security

## Reporting a vulnerability

Do not open a public issue for a security problem. Report it privately through GitHub's private vulnerability reporting: open the [Security tab](https://github.com/jamesarich/sofa-king/security/advisories/new) and choose Report a vulnerability. Include the app's version from Settings → About and its platform, or the server's commit or image revision, and the steps to reproduce. Leave out live tokens and private addresses; a redacted example is enough.

## The server

The server's [security policy](backend/SECURITY.md) covers its intended deployment, which token opens which routes, the request limits, the outbound address policy and a hardening checklist. A problem in the server, its API or its streams belongs there.

## The app

The app's [security page](app/docs/security.md) covers what it stores, where it sends the API token, and its cleartext HTTP.
