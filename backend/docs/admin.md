# Admin portal

The server serves an admin portal at `/admin` on its own port. Its server-rendered pages show whether everything is healthy, and edit channels, merges, groups and provider priority. It works from a phone browser and without JavaScript; with JavaScript, [htmx](dependencies.md#vendored-htmx) keeps the page in place after an action.

The portal calls the server's services directly, and it changes nothing the [admin API](api.md#admin-api) cannot. It also takes the same paths. Under a [lineup policy](lineup-policy.md), a channel edit is written into the station's override and applied, as `PATCH /api/v1/admin/lineup/{id}` does. A group or provider edit writes the whole policy against the revision the page showed and applies it, as `PUT /api/v1/admin/policy` does.

## Sign in

Open `http://SERVER_HOST:9193/admin` (port 8080 outside Docker) and enter the admin token: `SOFA_ADMIN_TOKEN`, or `SOFA_API_TOKEN` when there is no admin token. On a non-loopback bind the server generates an unset admin token at the first start. Read `adminToken` from `secrets.json` in the data directory, with `docker compose exec sofa-king-backend cat /config/secrets.json` under Docker. On loopback with neither token set, nobody can sign in, and the page says so.

A session lasts 12 hours from sign-in, or until you sign out. Sessions are held in memory, so a restart signs everyone out. Up to 32 sessions are kept; a new one replaces the one closest to expiry. Each client address may try to sign in 10 times a minute; beyond that the server answers `429 Too Many Requests` until the minute is up.

## Pages

The navigation groups the pages into the overview, Lineup (Channels, Merges, Required), Setup (Groups, Providers, Plex, Settings) and System (Jobs, Events, About). A count beside Merges, Required, Providers, Plex or Jobs says how many things there need a look. On a phone the navigation folds into a Menu button that carries the total. The footer of every signed-in page shows the server's revision and how long it has been up, and the theme switch: Auto follows the device's light or dark setting, Light and Dark pin one in a cookie.

| Page | What it shows | What it changes |
| --- | --- | --- |
| Overview | What needs attention, each with a link to where it is fixed and to the doc that explains it. Then the channels on and their guide coverage, the last Plex sync, required channels on, merges to settle, providers importing, who is watching and the last lineup run | Ignore an item until it changes, or show it again |
| Channels | The lineup, 100 channels a page. Each row has the number, name, group, source count, guide state and state. Search by name, number or station key, filter by group, state, guide state, required, override or changed in the last run, and sort by any column | Hide or show a channel from its row |
| Channel | One channel: its state, why it is off, its station key, guide, and sources in failover order with their provider, protocol and health | Rename, renumber or regroup it, and hide or show it |
| Merges | With a policy: the suggested aliases and open findings of a run over the current candidates, the learned aliases and the pairs kept apart | Accept a suggestion, dismiss it, or keep a learned pair apart. Each write applies the policy |
| Required | With a policy: the required stations from the last run, off ones first, with their pins, live source count, source health and state | Nothing |
| Groups | With a policy: the number blocks and the groups without one, the default group, the unmapped categories, the category map, the weak categories and the name patterns | Each of those |
| Providers | Each provider's channels, last import and status, and each guide's. With a policy, the provider entries in priority order, with what each takes and the stations each leads | With a policy: reorder, hide, show, add or remove provider entries |
| Plex | The last Plex sync with each tuner's DVR and mapped channels, each HDHomeRun device with its address and channel count, the devices the lineup needs, the M3U and XMLTV URLs, and the Plex setup steps | Nothing; the output token stays hidden until you choose to reveal it |
| Settings | The lineup policy's file, revision, contents and last run, every `SOFA_` variable as the server runs with it, each token's origin, the source file's providers and guides, and the newest policy changes | Apply the policy now; reveal the API token |
| Jobs | Every background job: each provider and guide import, the lineup policy run, Plex sync and the logo sweep, with its interval, last run, duration, result and next due time | Run a job now |
| Events | The newest 500 log lines at INFO and above since the server started, newest first, filtered by level and by text. With JavaScript the list refreshes every 10 seconds | Nothing |
| About | The revision, start time, schema version, JVM, OS, FFmpeg version, data directory, logging and memory, the status the admin API reports, and a support snapshot to paste into a bug report | Download the snapshot |

The overview lists, as needing attention: failed provider or guide imports, a failed Plex sync or tuners missing from Plex, and too few tuners for the lineup. It also lists required channels that are off, merge suggestions, open findings, unmapped categories, on-air channels whose guide ended, and channels waiting for a verified source. A failing import reads as a check while its last success is under a day old and as broken after that, with how long it has failed; one that has never imported since the server started is broken. Ignore keeps an item off the list, in the database, until its text changes, for instance when another provider fails or the reason changes; the ignored items sit under the list and can be shown again.

On the Channels page, the state filter offers on and off, and with a policy also unverified. The guide filter offers channels with programs, and channels whose guide is missing or ended. With a policy, "Has an override" keeps the stations whose policy entry holds something set by hand (a number a run wrote back does not count), and "Changed in the last run" keeps the entries the newest run that changed anything touched. A column header sorts by that column; the same header again reverses it. A row is tinted red when the channel is on and none of its sources is healthy, and amber when it is on and its guide ended or holds nothing ahead.

On the Jobs page, Run now starts an import or a guide import at once, and a policy run or a Plex sync without the usual quiet time. A job's result is its own summary, such as the channels and sources an import found, or the error class when it failed; the Events page has the lines it logged. The Events page keeps its lines in memory, so a restart empties it; the container's log has everything.

The About page's support snapshot is JSON with the admin API's status, the server facts, every job's last run and the overview's items. It holds no token, URL or secret. Copy it from the box, or download it with the button.

On the Channel page, the group is picked from the groups the lineup and the policy name. "Policy decides", or "No group" without a policy, removes a group set by hand.

On the Merges page, accepting a suggestion adds it to `names.aliases`, and dismissing it adds the pair to `names.never`. Keeping a learned alias apart adds the pair to `names.never` and drops the alias.

On the Groups page you can add a number block, move its first number or remove it, and set the default group. You can map a category or unmap it, add or remove a weak category, and add or remove a name pattern.

On the Providers page, with a policy, you can move an entry up or down and hide or show the stations an entry leads (its `enabled`). You can add an entry that takes every channel of a provider, or remove an entry.

Without a policy, the Merges, Required and Groups pages explain that they need one. Channel edits then go to the lineup itself, sending the changed fields the way `PATCH /api/v1/admin/lineup/{id}` does.

After a save the page says what was saved, once, and marks the channel it concerns. A channel edit applies to the policy file as it is when you save, since runs write numbers back to it often. When the policy refuses one, the error shows on the channel's page with the form to correct.

## Group and provider edits

A group or provider edit reads the policy file, makes the one change, validates the result and writes it with the revision the page showed, then applies it. Before it saves, the portal builds the lineup from the policy as it is and as the edit leaves it. An edit that changes no station saves at once. One that does waits for confirmation and lists how many channels turn off or on and which change group, number or name.

When the policy changed since the page loaded, the edit is not saved. The confirmation shows what it does against the policy as it is now, and confirming saves it against that revision. When the result would not be a valid policy, every validation error shows in the section the edit came from and nothing is written.

## Settings

The Settings page marks each `SOFA_` variable that has its default value, and notes the source and policy files when they were found in the data directory. It shows the newest 50 entries of the policy change log, with more on request.

It reports each token as generated (stored in `secrets.json`), set (in the environment or through its `_FILE` variable) or not set. It shows no token until you choose "Reveal the API token", which shows the API token the Sofa King app needs. It describes the source file by provider, kind, host and refresh interval, with header and option names. It never shows a URL path or query, a header value or an option value.

Device addresses on the Plex page use the host the page was reached by; extra devices use their own ports from `SOFA_HDHR_EXTRA_PORTS`.

## Security

- The portal is meant for the LAN, like the rest of the server. Reach it through a TLS-terminating proxy if the token crosses a network you do not control. Behind a proxy that sends `X-Forwarded-Proto: https`, the session cookie is marked `Secure`.
- The token is compared in constant time and never stored in the browser. Sign-in sets a `sofa_admin` cookie holding a random session ID, `HttpOnly`, `SameSite=Strict` and scoped to `Path=/admin`.
- Every page and action needs the session, whatever spelling of the path reaches it. The exceptions are the sign-in form and the brand icon its tab shows. Every action is a `POST` that carries the session's CSRF token.
- The portal opens nothing under `/api/`. API clients still need their bearer token.
- Pages are sent with `Cache-Control: no-store` and a content security policy that allows scripts from the server only. htmx and the icon are served from the server; the portal loads nothing from other hosts.
- The output token appears only on the page answering "Reveal the output token", and the API token only on the page answering "Reveal the API token". Neither is in the page source by default. Each reveal is a `POST` that needs the session and its CSRF token. No other token reaches any page.
- The theme cookie, `sofa_theme`, holds only `light` or `dark`, scoped to `Path=/admin` for a year. The docs links on the overview point at this repository on GitHub; the portal still loads nothing from other hosts.
