# Admin portal

The server serves a small admin portal at `/admin` on its own port: server-rendered pages for checking imports, editing the lineup, settling station merges and setting up Plex. It works from a phone browser and without JavaScript; with JavaScript, [htmx](dependencies.md) keeps the page in place after an action.

The portal calls the server's services directly. It changes nothing the [admin API](api.md#admin-api) cannot, and it takes the same paths: a lineup edit under a [lineup policy](lineup-policy.md) is written into the policy file, checked against the revision the page showed, then applied.

## Sign in

Open `http://SERVER_HOST:8080/admin` and enter the admin token: `SOFA_ADMIN_TOKEN`, or `SOFA_API_TOKEN` when no admin token is set. With neither set, nobody can sign in, and the page says so.

A session lasts 12 hours from sign-in, or until you sign out. Sessions are held in memory, so a restart signs everyone out. Up to 32 sessions are kept; a new one replaces the one closest to expiry.

## Pages

| Page | What it shows | What it changes |
| --- | --- | --- |
| Dashboard | Each provider's channels, last import and error code; each guide's channels, programmes, last import and error; the playback sessions; lineup totals (stations, enabled, and with a policy unverified, required and open findings from the last run) | Nothing |
| Lineup | The lineup, 100 stations a page, searchable by name, number or station key and filtered by group, state (enabled, hidden, and with a policy unverified) and required. Each row has the number, name, group, source count, guide status and state, with the reason when off | Hide or show a station, and rename, renumber or regroup it. The group is picked from the lineup's groups and, with a policy, the groups `numbering.groups` numbers; "Policy decides" (or "No group" without a policy) removes the group set by hand |
| Merges | With a policy: the learned aliases, the suggested aliases, the stations kept apart, the open findings and the unmapped categories of a run over the current feeds | Never merge a learned alias (adds the pair to `names.never` and drops the alias); accept a suggestion (adds it to `names.aliases`) or dismiss it (adds the pair to `names.never`). Each write applies the policy |
| Required | With a policy: the required stations from the last run, with their pins, live source count, source health and state | Nothing |
| Plex & players | The M3U playlist and XMLTV guide URLs, each HDHomeRun device with its address and channel count, the devices the lineup needs against those configured, and the Plex setup steps | Nothing; the output token stays hidden until you choose to reveal it |

Without a policy, the Merges and Required pages explain that they need one, and lineup edits go to the lineup itself, sending the changed fields the way `PATCH /api/v1/admin/lineup/{id}` does.

A lineup edit under a policy fails with "The policy revision is ..." when the policy changed since the page loaded, including through a run that wrote back numbers or learned aliases. Reload the page and repeat the edit. Every error shows on the page, next to the station it concerns.

Device addresses on the Plex page use the host the page was reached by; extra devices use their own ports from `SOFA_HDHR_EXTRA_PORTS`.

## Security

- The portal is meant for the LAN, like the rest of the server. Reach it through a TLS-terminating proxy if the token crosses a network you do not control; behind one that sends `X-Forwarded-Proto: https`, the session cookie is marked `Secure`.
- The token is compared in constant time and never stored in the browser. Sign-in sets a `sofa_admin` cookie holding a random session ID, `HttpOnly`, `SameSite=Strict` and scoped to `Path=/admin`.
- Every page and action needs the session, whatever spelling of the path reaches it, except the sign-in form. Every action is a `POST` that carries the session's CSRF token.
- The portal opens nothing under `/api/`. API clients still need their bearer token.
- Pages are sent with `Cache-Control: no-store` and a content security policy that allows scripts from the server only. htmx is served from the server; the portal loads nothing from other hosts.
- The output token appears only on the page answering "Reveal the output token", never in the page source by default.
