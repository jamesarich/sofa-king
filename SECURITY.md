# Security

## Reporting a vulnerability

Do not open a public issue for a security problem. Report it privately through GitHub's private vulnerability reporting on this repository (Security tab, then Report a vulnerability). Include the commit or version, the route or code path involved, and the steps to reproduce. Leave out live tokens, provider URLs with credentials and private addresses; a redacted example is enough.

## Intended deployment

The server is designed for a private network. It has no accounts, no per-user access control and no TLS. It binds to loopback by default, and a non-loopback bind refuses to start without `SOFA_API_TOKEN`. Keep it on the LAN, do not forward its port to the internet, and put it behind a VPN or a TLS-terminating reverse proxy if it must be reached from outside.

## Credentials and the routes they open

| Credential | Sent as | Opens |
| --- | --- | --- |
| API token (`SOFA_API_TOKEN`) | `Authorization: Bearer` | `/api/v1/*`, except `/api/v1/admin/*` when an admin token is set. Also accepted as a bearer on `/output/*` |
| Admin token (`SOFA_ADMIN_TOKEN`) | `Authorization: Bearer` | `/api/v1/admin/*`. The API token is refused there once this is set |
| Admin portal session | `sofa_admin` cookie, set by signing in at `/admin` with the admin token (or the API token when no admin token is set) | `/admin/*`, the [admin portal](docs/admin.md). Actions also need the session's CSRF token |
| Output token (`SOFA_OUTPUT_TOKEN`) | `?token=` query or bearer | `/output/*`: the playlist, the guide and the stream URLs |
| None | | `/healthz`, `/readyz` and `/logos/{key}` |
| None; client address allow-list | | The HDHomeRun routes (`/discover.json`, `/lineup.json`, `/lineup_status.json`, `/lineup.post`, `/device.xml`, `/auto/v{number}`). `SOFA_HDHR_ALLOW` admits listed addresses and answers everyone else `403`; unset admits the whole LAN |
| Session URL | | `/media/sessions/{id}/{epoch}/{token}`. The token is random and the URL ends with its session |

Notes on the table:

- A token that is not configured leaves its routes open. With no API token, every `/api/` route is open, and with neither output nor API token, `/output/*` is open. This is for loopback development.
- Tokens are compared in constant time. The API token is never accepted in a query string.
- A token in a URL lands in player configuration, screenshots and proxy logs. That is why the output token is separate from the API token and opens nothing but `/output/*`.
- Logo URLs and HDHomeRun routes carry no token because the clients that fetch them, Plex and players, send none. Logos are fetched only for URLs the lineup names.

## Outbound requests

The server makes requests to feed, guide, logo and stream hosts that you configure. The upstream address policy applies to all of them:

- Only `http` and `https` URLs without user information are fetched.
- Loopback, link-local, unspecified and multicast addresses are refused, including as redirect targets. Private LAN addresses are allowed, because LAN restream proxies are a common setup.
- Hostnames are not resolved before the request, so a name that resolves to a refused address is not caught by this check.
- Provider headers, such as an authorization header, go only to the configured origin. A cross-origin CDN request receives only `User-Agent`, `Accept`, `Accept-Language`, `Origin` and `Referer`.
- Redirects are limited to five, and an HTTPS redirect cannot downgrade to HTTP.

Details are in [live TV: upstream addresses and provider headers](docs/live-tv.md#provider-headers).

## Hardening checklist

- Set `SOFA_API_TOKEN` and `SOFA_ADMIN_TOKEN` to values of at least 32 characters, from `openssl rand -hex 32`.
- Set `SOFA_OUTPUT_TOKEN` when players or Plex use the outputs, from `openssl rand -hex 16`.
- Set `SOFA_HDHR_ALLOW` to the address of the Plex server, or set `SOFA_HDHR_ENABLED=false` when Plex is not used.
- Keep `data/` and the source file private. The database holds native provider session tokens, and the source file can hold feed URLs with credentials. The Docker files in `deploy/` are ignored by Git.
- With [automatic updates](docs/deploy.md#update-automatically), log Docker in to the registry with a token that has only the `read:packages` scope. The updater's database backups hold the same session tokens as the database and are readable by root alone.
- Use HTTPS, through a reverse proxy, for any token that crosses a network you do not control.
- Rotate tokens with the steps in [operations](docs/operations.md#rotating-tokens).
