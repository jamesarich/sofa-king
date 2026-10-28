# Security

This page covers the server's intended deployment, which credential opens which routes, the request limits, the outbound address policy and a hardening checklist. To report a vulnerability, follow the [repository's security policy](../../SECURITY.md#reporting-a-vulnerability). The [app's security page](../../app/docs/security.md) covers what the app stores and where it sends the token.

## Intended deployment

The server is designed for a private network. It has no accounts, no per-user access control and no TLS. It binds to loopback by default. On a non-loopback bind, such as the Docker image's, it always has an API token and an admin token: an unset one is generated at the first start, as the next section describes. Off loopback it advertises its name, address, port and version on the LAN over mDNS; `SOFA_MDNS=false` stops that. Keep it on the LAN and do not forward its port to the internet. Put it behind a VPN or a TLS-terminating reverse proxy if it must be reached from outside.

## Credentials and the routes they open

| Credential | Sent as | Opens |
| --- | --- | --- |
| API token (`SOFA_API_TOKEN`) | `Authorization: Bearer` | `/api/v1/*`, except `/api/v1/admin/*` when an admin token is set. Also accepted as a bearer on `/output/*` |
| Admin token (`SOFA_ADMIN_TOKEN`) | `Authorization: Bearer` | `/api/v1/admin/*`. The API token is refused there once this is set |
| Admin portal session | `sofa_admin` cookie, set by signing in at `/admin` with the admin token (or the API token when no admin token is set) | `/admin/*`, the [admin portal](admin.md). Actions also need the session's CSRF token |
| Pairing code | `POST /api/v1/pair` body, no bearer | Issued from the portal's Devices page, six digits, valid 10 minutes, one at a time, spent on the first match and voided after five wrong guesses. A match answers with a device token |
| Device token (`sk_…`, kind `api`) | `Authorization: Bearer` | What the API token opens, except `/api/v1/admin/*` and the hooks. One per paired device; pairing the device again or forgetting it on the Devices page revokes it |
| Read token (`sk_…`, kind `read`) | `Authorization: Bearer`; `?token=` on the calendar | `GET /api/v1/status`, `/output/recordings.ics`, and `GET` of the admin API's status, sessions, guides, lineup, policy results and libraries, as [the API lists](api.md#tokens). Made on the Settings page for a dashboard or a calendar |
| Hook token (`sk_…`, kind `hook`) | `Authorization: Bearer`, a Basic password, or `X-MediaBrowser-Token` or `X-Emby-Token` | The [hooks](api.md#hooks) only: `/api/v1/hooks/*` and the Emby-style `/Library/Media/Updated`, `/Notifications/Admin`, `/System/Configuration` and `/Items` (also under `/emby` and `/mediabrowser`). Made on the Settings page for Sonarr and Radarr. The API token opens the hooks too |
| Output token (`SOFA_OUTPUT_TOKEN`) | `?token=` query or bearer | `/output/*`: the playlist, the guide and the stream URLs |
| None | | `/healthz`, `/readyz`, `/logos/{key}`, `/artwork/{id}/{role}`, `/artwork/{id}/scrub/{sheet}`, the admin sign-in page (`GET /admin` and `POST /admin/login`), the portal icon `/admin/static/icon.svg` and its stylesheet `/admin/static/admin.css` |
| None; client address allow-list | | The HDHomeRun routes (`/discover.json`, `/lineup.json`, `/lineup_status.json`, `/lineup.post`, `/device.xml`, `/auto/v{number}`). Only the addresses in `SOFA_HDHR_ALLOW` and those an admin allowed on the portal's Plex page get in; everyone else gets `403` |
| Session URL | | `/media/sessions/{id}/{epoch}/{token}`. `{token}` is random per resource, and the URL stops working when the session ends or its epoch changes |
| Library playback URL | | `/media/vod/{id}/{epoch}/{secret}/{name}`. `{secret}` is random per session, and the URL serves only that session's file or HLS output until the session ends or its epoch changes |

Notes on the table:

- On a non-loopback bind, an API or admin token that the environment leaves unset is generated: 32 random bytes from the JVM's `SecureRandom`, as 64 hex characters. Both are kept in `secrets.json` in the data directory, created with mode `600`, replaced by an atomic rename and reused on later starts. A token set in the environment, or read from the file its `_FILE` variable names, always wins and is never written there.
- No token is ever logged. The start that generates the admin token logs the path of `secrets.json` and the command that reads it. A signed-in admin can reveal the API token on the portal's Settings page and the output token on its Plex page, each through a `POST` with the session's CSRF token.
- On a non-loopback bind, the HDHomeRun routes admit no address until one is allowed, on the portal's Plex page or in `SOFA_HDHR_ALLOW`. The server keeps the last ten refused addresses in memory, for the portal to offer. On loopback, with neither list, every request from this machine is admitted, and one that carries a proxy's forwarding header is refused, as the API refuses it.
- On a loopback bind, a token that is not configured leaves its routes open. With no API token, every `/api/` route is open to this machine only: a request whose `Host` is not a loopback name or address, whose `Origin` is another site, or that carries a proxy's `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host` or `X-Real-IP` header answers `403 LOOPBACK_ONLY`, so a web page cannot drive the API and a reverse proxy cannot expose it. With neither output nor API token, `/output/*` is open to this machine on the same terms. This is for local development.
- Tokens are compared in constant time. The API token is never accepted in a query string.
- The server keeps only the SHA-256 of an `sk_` token, with its label, kind, device and when it was made, last used (to the minute) and revoked; a revoked token never works again. The portal and `POST /api/v1/admin/tokens` show a new read or hook token's secret once. A device paired before devices had tokens of their own holds the API token; the Devices page says so, and pairing it again moves it to its own.
- A token in a URL lands in player configuration, screenshots and proxy logs. That is why the output token is separate from the API token and opens nothing but `/output/*`.
- Logo URLs and HDHomeRun routes carry no token because the clients that fetch them, Plex and players, send none. Logos are fetched only for URLs the lineup names. Artwork URLs carry none for the same reason, and serve only the poster or backdrop a library holds for that item, so anyone who can reach the port can fetch a library's images by item id.
- Unless `SOFA_CORS_ORIGINS` is set, the server sends no CORS headers, so only pages on its own origin can call it from a browser. A page on a listed origin may call any route with the token it holds, as a bearer header, and read the `ETag` and `X-Request-Id` response headers. Credentialed cross-origin requests are not allowed, so the admin portal's cookie never works from another origin.

## Request limits

- Request bodies are capped at 64 KiB, at 4 MiB for a whole policy written through `PUT /api/v1/admin/policy` or the portal's group and provider edits, and at 8 GiB for a database uploaded to the portal's Backup page, which writes it to the data directory as it arrives. A larger body answers `413 REQUEST_TOO_LARGE` before any token is checked.
- The web app under `/app/` takes no token: it is the public bundle the snapshot release publishes, and asks for its token itself. Its page shares the server's origin with the admin portal, so its policy allows scripts and connections only from that origin (and fetches and images from HTTPS hosts, for the guide's program images), and frames from none. The app draws to a canvas and renders no markup from server data.
- `POST /api/v1/pair` is the one `/api/v1/*` route that takes no token. It accepts 10 attempts a minute from each client address, like the login, and a six-digit code with a 10-minute life leaves at most 50 guesses against one million codes.
- `POST /admin/login` accepts 10 attempts a minute from each client address and answers `429 Too Many Requests` beyond that, even for the right token. Behind a reverse proxy every sign-in comes from the proxy's address and shares that budget.
- A rejected token is logged as `auth failed route=ROUTE remote=ADDRESS`, at most 30 lines a minute. The route is the path without its query, and the token is never logged.

## Outbound requests

The server collects no telemetry, analytics or crash reports and reports nothing about its users. Its own requests are the ones below, and the update check's request to the image's container registry ([about](admin.md#update)).

The server makes requests to the playlist, guide, logo and stream hosts that you configure, and, with a TMDB token, to `api.themoviedb.org` and `image.tmdb.org` for library details and artwork. With a Music library, it asks `musicbrainz.org` about its albums and artists by name and MusicBrainz id, `coverartarchive.org` and `archive.org` for album covers by id, and `www.wikidata.org`, `en.wikipedia.org`, `commons.wikimedia.org` and `upload.wikimedia.org` for artists' biographies and pictures, and `api.listenbrainz.org` and `labs.api.listenbrainz.org` for artists' most played songs and similar artists by MusicBrainz id (with a ListenBrainz user token, sent to `api.listenbrainz.org` only), and `lrclib.net` for a song's lyrics by its title, artist, album and length, unless `SOFA_MUSICBRAINZ=false`, and, with a fanart.tv key, `webservice.fanart.tv` by MusicBrainz id and `assets.fanart.tv` for artists' pictures and backdrops and album covers. The upstream address policy applies to all of them:

- Only `http` and `https` URLs without user information are fetched.
- Loopback, link-local, unspecified and multicast addresses are refused, including as redirect targets the server follows, and so are numeric hosts other than a plain dotted quad (`0x7f000001`, `0177.0.0.1`, `127.1`), which the system resolver reads as addresses. Private LAN addresses are allowed, because LAN restream proxies are a common setup. This covers FFmpeg's reads too: the server fetches every redirect and every variant, segment and key URL a playlist names for FFmpeg, and a refused hop fails that read.
- Hostnames are not resolved before the request, so a name that resolves to a refused address is not caught by this check.
- Provider headers, such as an authorization header, go only to the configured origin. A cross-origin CDN request receives only `User-Agent`, `Accept`, `Accept-Language`, `Origin`, `Referer` and a provider's region address (`X-Forwarded-For`), since a stream's keys and segments answer by region too.
- Redirects are limited to five, and an HTTPS redirect cannot downgrade to HTTP.
- FFmpeg, which probes sources, records them and remuxes them for Plex, reads them only through the server's relay on `127.0.0.1`, on a port of its own that is no part of the API. Each FFmpeg run has its own random credential, which it sends as a request header and which is never logged; the relay answers nothing without it. The relay adds the provider headers, scoped to their origin as above, so FFmpeg never holds them. FFmpeg may open only the `http`, `tcp` and `crypto` protocols, so a playlist that names `https:`, `file:`, `concat:` or any other protocol can neither reach another host nor make it read files in the container. FFmpeg and FFprobe read local files only for library items under `SOFA_MEDIA_DIR` and recordings under `SOFA_RECORDINGS_DIR`, by paths the server chose, never by a client's.

Details are in [live TV: upstream addresses and provider headers](live-tv.md#provider-headers).

[Notifications](notifications.md) go only to the targets an admin adds, and the upstream address policy does not apply to them: a target may be a LAN or loopback address, such as Home Assistant or an Apprise API container beside the server. Its URL must be `http` or `https` without user information, redirects are not followed, and its secret is never returned by the API or written to the log, which names a target by its id.

## Hardening checklist

- Let the server generate the API and admin tokens, or set them to values of at least 32 characters from `openssl rand -hex 32`. Prefer the `_FILE` variables with Docker secrets over plain values in `.env`.
- The output token is generated at the first start like the others. Rotate it when a playlist URL has been shared further than you meant, as [operations](operations.md#rotating-tokens) describes.
- Mount the media folder read only (`:ro`), as [media libraries](libraries.md#mount-the-media-folder) shows; the server never writes to it. Pass `SOFA_TMDB_TOKEN` through `SOFA_TMDB_TOKEN_FILE` like the other tokens.
- Allow only the Plex server's address onto the tuners, or set `SOFA_HDHR_ENABLED=false` when Plex is not used.
- Keep the data directory (`deploy/config` with Docker) private to the user the server runs as. The database holds native provider session tokens, the [playback history](api.md#playback-history) (what each device and player address watched, for `SOFA_HISTORY_DAYS` days), `secrets.json` holds the generated tokens and the TMDB token the portal saved, and the source file can hold playlist URLs with credentials. `deploy/.env` and `deploy/config/` are ignored by Git.
- With [automatic updates](deploy.md#update-automatically), the updater's database backups hold the same session tokens as the database. Those in the config folder are readable by the container's user alone, and the fallback backups in the Compose directory by root alone.
- [Inspect the published image's provenance and SBOM](deploy.md#inspect-the-published-image) before you run it.
- Use HTTPS, through a reverse proxy, for any token that crosses a network you do not control.
- Rotate tokens with the steps in [operations](operations.md#rotating-tokens).
