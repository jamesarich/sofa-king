# Admin portal

The server serves an admin portal at `/admin` on its own port. Its server-rendered pages show whether everything is healthy, and edit channels, merges, groups and provider priority. It works from a phone browser and without JavaScript; with JavaScript, [htmx](dependencies.md#vendored-htmx) keeps the page in place after an action.

The portal calls the server's services directly, and apart from its runtime settings, the update request and its own controls (stop a session, run a job or a probe now, ignore a home page item), it changes nothing the [admin API](api.md#admin-api) cannot. It also takes the same paths. Under a [lineup policy](lineup-policy.md), a channel edit is written into the station's override and applied, as `PATCH /api/v1/admin/lineup/{id}` does. A group or provider edit writes the whole policy against the revision the page showed and applies it, as `PUT /api/v1/admin/policy` does.

## Sign in

Open `http://SERVER_HOST:9193/admin` (port 8080 outside Docker) and enter the admin token: `SOFA_ADMIN_TOKEN`, or `SOFA_API_TOKEN` when there is no admin token. On a non-loopback bind the server generates an unset admin token at the first start. Read `adminToken` from `secrets.json` in the data directory, with `docker compose exec sofa-king-backend cat /config/secrets.json` under Docker. On loopback with neither token set, nobody can sign in, and the page says so.

A session lasts 12 hours from sign-in, or until you sign out. Sessions are held in memory, so a restart signs everyone out. Up to 32 sessions are kept; a new one replaces the one closest to expiry. Each client address may try to sign in 10 times a minute; beyond that the server answers `429 Too Many Requests` until the minute is up.

## Pages

The navigation has six destinations: Home, Channels, Sources, Players, Settings and System. Channels, Players and System hold more than one page, shown as tabs under the heading: Channels, Required, Merges and Groups; Devices and Plex; About, Jobs, Events and API. A count beside a destination says how many things there need a look, and the tab that holds them carries the same count. On a phone the navigation folds into a Menu button that carries the total. The footer of every signed-in page shows the server's revision and how long it has been up, and the theme switch: Auto follows the device's light or dark setting, Light and Dark pin one in a cookie.

| Page | What it shows | What it changes |
| --- | --- | --- |
| Home | While the server has no source file, or no policy and no lineup, the setup steps: choose services, build the lineup. Then what needs attention, each with a link to where it is fixed and to the doc that explains it. Then the channels on and their guide coverage, the last Plex sync, required channels on, merges to settle, providers importing, who is watching (the channel, the programme on now, the player and its address, how long it has watched, the bytes sent and when its lease ends) and the last lineup run | Ignore an item until it changes, or show it again; stop a session |
| Channels | The lineup, 100 channels a page. Each row has the number, name, group, source count, guide state and state. Search by name, number or station key, filter by group, state, guide state, required, override or changed in the last run, and sort by any column | Hide or show a channel from its row; select channels and hide, show or regroup them together |
| Channel | One channel: its state, why it is off, its station key, guide, and sources in failover order with their provider, protocol and health | Rename, renumber or regroup it, and hide or show it; disable, enable or probe a source |
| Merges | With a policy: the suggested aliases and open findings of a run over the current candidates, the learned aliases and the pairs kept apart | Accept a suggestion, dismiss it, or keep a learned pair apart. Each write applies the policy |
| Required | With a policy: the required stations from the last run, off ones first, with their pins, live source count, source health and state | Nothing |
| Groups | With a policy: the number blocks and the groups without one, the default group, the unmapped categories, the category map, the weak categories and the name patterns | Each of those |
| Sources | Every native service as a switch with the options it takes, the M3U playlists, each provider's channels, last import and status, the source file's providers and guides by kind, host and refresh interval (folded, with header and option names but never a value), and each guide's import. With a policy, the provider entries in priority order, with what each takes and the stations each leads | Turn services on or off, add or remove an M3U playlist, each after a confirmation and followed by a restart; with a policy, reorder, hide, show, add or remove provider entries |
| Plex | The last Plex sync with each tuner's DVR and mapped channels, each HDHomeRun device with its address and channel count, the devices the lineup needs, the M3U and XMLTV URLs, and the Plex setup steps | Nothing; the output token stays hidden until you choose to reveal it |
| Devices | The devices running the app against this server: each one's name and platform, how many channels it hides, its favorites and recent channels, when it last saved them and when it was first and last seen. Above them, pairing: a six-digit code and its QR code while one is live | Show a pairing code, replace or cancel it; forget a device |
| Settings | The runtime settings. Under Advanced: the lineup policy's file, revision, contents and last run, every startup `SOFA_` variable as the server runs with it, each token's origin, and the newest policy changes | Save the runtime settings; under Advanced, apply the policy now and reveal the API token |
| Jobs | Every background job: each provider and guide import, the lineup policy run, Plex sync, the logo sweep and the update check, with its interval, last run, duration, result and next due time | Run a job now |
| Events | The newest 500 log lines at INFO and above since the server started, newest first, filtered by level and by text. With JavaScript the list refreshes every 10 seconds | Nothing |
| About | Whether the server is up to date: the running and published version and revision, and what the host's updater did last. Then the start time, schema version, JVM, OS, FFmpeg version, data directory, logging and memory, the status the admin API reports, and a support snapshot to paste into a bug report | Check the registry now; ask the updater to update now; download the snapshot |
| API | Every HTTP route the server serves, grouped by area, with its token, parameters, body and responses, from the server's OpenAPI document | Download the OpenAPI document |

The home page lists, as needing attention: failed provider or guide imports, a failed Plex sync or tuners missing from Plex, too few tuners for the lineup, and no import yet once the server has been up for ten minutes. It also lists required channels that are off, merge suggestions, unmapped categories and on-air channels whose guide ended. A failing import reads as a check while its last success is under a day old and as broken after that, with how long it has failed; one that has never imported since the server started is broken. Ignore keeps an item off the list, in the database, until its text changes, for instance when another provider fails or the reason changes; the ignored items sit under the list and can be shown again.

On the home page, Stop asks once, then ends the session: the player sees its stream end and may open the channel again. An M3U or Plex player that keeps requesting media opens a new session at once.

On the Channels page, the state filter offers on and off, and with a policy also unverified. The guide filter offers channels with programs, and channels whose guide is missing or ended. With a policy, "Has an override" keeps the stations whose policy entry holds something set by hand (a number a run wrote back does not count), and "Changed in the last run" keeps the entries the newest run that changed anything touched. A column header sorts by that column; the same header again reverses it. A row is tinted red when the channel is on and none of its sources is healthy, and amber when it is on and its guide ended or holds nothing ahead.

On the Jobs page, Run now starts an import or a guide import at once, and a policy run or a Plex sync without the usual quiet time. A job's result is its own summary, such as the channels and sources an import found, or the error class when it failed; the Events page has the lines it logged. The Events page keeps its lines in memory, so a restart empties it; the container's log has everything.

The About page's support snapshot is JSON with the admin API's status, the server facts, every job's last run and the overview's items. It holds no token, URL or secret. Copy it from the box, or download it with the button.

On the Channel page, the group is picked from the groups the lineup and the policy name. "Policy decides", or "No group" without a policy, removes a group set by hand. Each source has Disable, which asks once and then takes the source out of selection as [source health](source-health.md#inspect-or-disable-a-source) describes, Enable, which puts it back at once, and Probe now, which puts the source at the front of the probe queue; the health updates when the probe is done. Probe now is absent when probes are off.

On the Channels page, each row has a checkbox, and "Select all on this page" ticks the page. Hide, Show and Set group act on the selection: with a policy, one edit writes every selected station's override and applies, after the confirmation the [group and provider edits](#group-and-provider-edits) describe; without one, the edit changes the lineup entries after a confirmation that lists what turns off or on. The selection and the filters survive the edit.

On the Merges page, accepting a suggestion adds it to `names.aliases`, and dismissing it adds the pair to `names.never`. Keeping a learned alias apart adds the pair to `names.never` and drops the alias.

On the Groups page you can add a number block, move its first number or remove it, and set the default group. You can map a category or unmap it, add or remove a weak category, and add or remove a name pattern.

On the Sources page, with a policy, you can move an entry up or down and hide or show the stations an entry leads (its `enabled`). You can add an entry that takes every channel of a provider, or remove an entry.

Without a policy, the Merges, Required and Groups pages explain that they need one. Channel edits then go to the lineup itself, sending the changed fields the way `PATCH /api/v1/admin/lineup/{id}` does.

After a save the page says what was saved, once, and marks the channel it concerns. A channel edit applies to the policy file as it is when you save, since runs write numbers back to it often. When the policy refuses one, the error shows on the channel's page with the form to correct.

## Devices

Show a pairing code issues a six-digit code that stands in for the API token for ten minutes and one device: on the TV, open the app's Settings, enter the server address the page shows and the code, and choose Pair. The page also draws the code and the address as a QR code; an Android phone, an iPhone or an iPad with the app installed scans it with its camera and pairs at once. One code is live at a time; New code replaces it, Cancel the code drops it, and five wrong attempts void it. The address shown is the one your browser reached the server at, so open the portal the way the TV will reach it.

A device appears once it pairs or saves its preferences (its hidden channels, favorites order and recent channels, kept per device so a reinstall or a second device can take them over). Forget drops what the server keeps for a device; the device itself keeps its settings and saves them again on its next change. The API token never appears on this page; the Settings page reveals it under Advanced.

## Update

The About page starts with the update picture. Running is this server's version and revision, from the image's build. Published is what the registry holds under the image's tag (`SOFA_UPDATE_IMAGE`, `ghcr.io/jamesarich/sofa-king:main` unless set): the server reads the revision and version from the tag's index annotations, two minutes after it starts and every six hours after, as the `update` job on the Jobs page; Check now runs it at once. When the published revision differs from the running one, the page says an update is available.

Updater is what the host's [updater](deploy.md#update-automatically) reported in `update-status.json` in the data directory: when it last checked and whether it found nothing new, installed an image, rolled one back, skipped one that failed before, or failed. A report over an hour old is marked as not checking in, since the timer runs every 15 minutes. Without a report the page says the updater has not reported and offers no Update now.

Update now asks once, then writes `update-requested` in the data directory. The updater's path unit starts it when the file appears, and the script removes the file as it starts, then pulls, compares and swaps as it does on the timer. The page shows the pending request until the updater takes it. The portal itself never pulls an image or restarts the container; it has no Docker socket.

## Setup

The Sources page edits the source file, so a server needs no hand-written `sources.json`. Every [native service](providers.md) is a switch with what it carries; PBS takes a ZIP code, Local Now a DMA code and market, Samsung TV Plus a region and an HDHomeRun its address, and the other options stay as the file has them. Save asks once, naming the services it turns on or off, then writes the file and restarts the server. The M3U playlists panel lists the file's playlist providers by host and removes one, and adds one from an ID, a playlist URL and an optional guide URL; a token in the URL goes into the file and never onto a page. Every write is checked the way the server checks the file at start, so a refused one changes nothing and says why.

The file goes where the server reads it: the path `SOFA_SOURCES` names, else `sources.json` in the data directory. A `${NAME}` reference in the file stays a reference. Providers, guides and the policy are built from the files at start, so each write ends the process once the page has answered; under the Compose file, `restart: unless-stopped` starts it again within seconds, and elsewhere start it again yourself. Sessions are kept in memory, so the restart signs everyone out, and the page that answers says so and opens the portal again after twenty seconds. Anyone watching loses the stream and can open the channel again.

While the server has no source file, or no lineup policy and an empty lineup, the home page starts with the two setup steps. Choose services opens the Sources page with every service that lists channels without an option already ticked. Build the lineup, with sources and no policy, asks once and writes the [starter policy](starter-lineup.md): one station per channel, the configured services in the server's priority order (an HDHomeRun tuner first), the shipped group map and number blocks, and `requireVerified`; then the server restarts and runs it. The steps leave the page once both files exist.

## Group and provider edits

A group or provider edit reads the policy file, makes the one change, validates the result and writes it with the revision the page showed, then applies it. Before it saves, the portal builds the lineup from the policy as it is and as the edit leaves it. An edit that changes no station saves at once. One that does waits for confirmation and lists how many channels turn off or on and which change group, number or name.

When the policy changed since the page loaded, the edit is not saved. The confirmation shows what it does against the policy as it is now, and confirming saves it against that revision. When the result would not be a valid policy, every validation error shows in the section the edit came from and nothing is written.

## Settings

The Settings page shows the runtime settings: the log level, how many days ahead the XMLTV guide reaches, the Plex sync interval, the streams each HDHomeRun device may open and the sessions the server holds at once. Save applies them at once, keeps them in the database across restarts, and they take precedence over the environment; the page says beside each which value it follows. Saving the environment's own value puts a setting back to following the environment, as "Back to the environment's values" does for all of them. A value out of range is refused with the form kept; the streams per device cannot exceed the sessions at once. Plex reads a device's tuner count when it adds the device, so a changed count shows in Plex after the device is re-added, while the server enforces it at once. A saved value that no longer fits is dropped at start, with a warning in the log.

Advanced, folded by default, holds the lineup policy with Apply now, the startup settings and the policy change log. It opens on its own after an action inside it, and `?advanced=1` opens it. The startup settings mark each `SOFA_` variable that has its default value, and notes the source and policy files when they were found in the data directory. It shows the newest 50 entries of the policy change log, with more on request.

It reports each token as generated (stored in `secrets.json`), set (in the environment or through its `_FILE` variable) or not set. It shows no token until you choose "Reveal the API token", which shows the API token the Sofa King app needs. The Sources page describes the source file by provider, kind, host and refresh interval, with header and option names. It never shows a URL path or query, a header value or an option value.

Device addresses on the Plex page use the host the page was reached by; extra devices use their own ports from `SOFA_HDHR_EXTRA_PORTS`.

## Security

- The portal is meant for the LAN, like the rest of the server. Reach it through a TLS-terminating proxy if the token crosses a network you do not control. Behind a proxy that sends `X-Forwarded-Proto: https`, the session cookie is marked `Secure`.
- The token is compared in constant time and never stored in the browser. Sign-in sets a `sofa_admin` cookie holding a random session ID, `HttpOnly`, `SameSite=Strict` and scoped to `Path=/admin`.
- Every page and action needs the session, whatever spelling of the path reaches it. The exceptions are the sign-in form, the brand icon its tab shows and the stylesheet. Every action is a `POST` that carries the session's CSRF token.
- The portal opens nothing under `/api/`. API clients still need their bearer token.
- Pages are sent with `Cache-Control: no-store` and a content security policy that allows scripts from the server only. htmx and the icon are served from the server; the portal loads nothing from other hosts.
- The output token appears only on the page answering "Reveal the output token", and the API token only on the page answering "Reveal the API token". Neither is in the page source by default. Each reveal is a `POST` that needs the session and its CSRF token. No other token reaches any page.
- The theme cookie, `sofa_theme`, holds only `light` or `dark`, scoped to `Path=/admin` for a year. The docs links on the home page point at this repository on GitHub; the portal still loads nothing from other hosts.
