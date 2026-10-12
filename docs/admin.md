# Admin portal

The server serves an admin portal at `/admin` on its own port: server-rendered pages that show whether everything is healthy, and that edit channels, merges, groups and provider priority. It works from a phone browser and without JavaScript; with JavaScript, [htmx](dependencies.md) keeps the page in place after an action.

The portal calls the server's services directly. It changes nothing the [admin API](api.md#admin-api) cannot, and it takes the same paths: a channel edit under a [lineup policy](lineup-policy.md) is written into the station's override and applied, as `PATCH /api/v1/admin/lineup/{id}` does, and a group or provider edit writes the whole policy against the revision the page showed and applies it, as `PUT /api/v1/admin/policy` does.

## Sign in

Open `http://SERVER_HOST:8080/admin` and enter the admin token: `SOFA_ADMIN_TOKEN`, or `SOFA_API_TOKEN` when no admin token is set. With neither set, nobody can sign in, and the page says so.

A session lasts 12 hours from sign-in, or until you sign out. Sessions are held in memory, so a restart signs everyone out. Up to 32 sessions are kept; a new one replaces the one closest to expiry.

## Pages

The navigation groups the pages into the overview, Lineup (Channels, Merges, Required) and Setup (Groups, Providers, Plex, Settings). A count beside Merges, Required, Providers or Plex says how many things there need a look.

| Page | What it shows | What it changes |
| --- | --- | --- |
| Overview | What needs attention, each with a link to where it is fixed: failed provider or guide imports, a failed Plex sync or tuners missing from Plex, too few tuners for the lineup, required channels off, merge suggestions, open findings, unmapped categories, on-air channels whose guide ended, and channels waiting for a verified source. Then channels on, guide coverage of the channels on, the last Plex sync, required channels on, merges to settle, providers importing, who is watching and the last lineup run | Nothing |
| Channels | The lineup, 100 channels a page, searchable by name, number or station key and filtered by group, state (on, off, and with a policy unverified), guide (has programmes, or missing or ended) and required. Each row has the number, name, group, source count, guide state and state | Hide or show a channel from its row |
| Channel | One channel: its state, why it is off, its station key, guide, and sources in failover order with their provider, protocol and health | Rename, renumber or regroup it, and hide or show it. The group is picked from the groups the lineup and the policy name; "Policy decides" (or "No group" without a policy) removes the group set by hand |
| Merges | With a policy: the suggested aliases and the open findings of a run over the current feeds, the learned aliases and the pairs kept apart | Accept a suggestion (adds it to `names.aliases`) or dismiss it (adds the pair to `names.never`); never merge a learned alias (adds the pair to `names.never` and drops the alias). Each write applies the policy |
| Required | With a policy: the required stations from the last run, off ones first, with their pins, live source count, source health and state | Nothing |
| Groups | With a policy: each group's number block with its range and channels on, the groups in the lineup without a block, the default group, the categories the last run left unmapped, the category map by group, the weak categories and the name patterns | Add a block, move its first number or remove it; set the default group; map a category or unmap it; add or remove a weak category; add or remove a name pattern |
| Providers | Each provider's channels, last import and status, and each guide's; with a policy, the provider entries in priority order with what each takes and the stations each provider leads | With a policy: move an entry up or down, hide or show the stations an entry leads (its `enabled`), add an entry that takes every channel of a provider, or remove an entry |
| Plex | The last Plex sync with each tuner's DVR and mapped channels, each HDHomeRun device with its address and channel count, the devices the lineup needs, the M3U playlist and XMLTV guide URLs, and the Plex setup steps | Nothing; the output token stays hidden until you choose to reveal it |
| Settings | The lineup policy's file, revision, contents and last run; every `SOFA_` variable as the server runs with it, marked when it is the default; the source file's providers and guides; and the newest 50 entries of the policy change log, with more on request | Apply the policy now |

Without a policy, the Merges, Required and Groups pages explain that they need one, and channel edits go to the lineup itself, sending the changed fields the way `PATCH /api/v1/admin/lineup/{id}` does.

After a save the page says what was saved, once, and marks the channel it concerns. A channel edit applies to the policy file as it is when you save, since runs write numbers back to it often. When the policy refuses one, the error shows on the channel's page with the form to correct.

## Group and provider edits

A group or provider edit reads the policy file, makes the one change, validates the result and writes it with the revision the page showed, then applies it. Before it saves, the portal builds the lineup from the policy as it is and as the edit leaves it. An edit that changes no station saves at once. One that does waits for confirmation and lists how many channels turn off or on and which change group, number or name.

When the policy changed since the page loaded, the edit is not saved: the confirmation shows what it does against the policy as it is now, and confirming saves it against that revision. When the result would not be a valid policy, every validation error shows in the section the edit came from and nothing is written.

The settings page reports each token only as set or not set, and describes the source file by provider, kind, host and refresh interval, with header and option names but never a URL path or query, a header value or an option value.

Device addresses on the Plex page use the host the page was reached by; extra devices use their own ports from `SOFA_HDHR_EXTRA_PORTS`.

## Security

- The portal is meant for the LAN, like the rest of the server. Reach it through a TLS-terminating proxy if the token crosses a network you do not control; behind one that sends `X-Forwarded-Proto: https`, the session cookie is marked `Secure`.
- The token is compared in constant time and never stored in the browser. Sign-in sets a `sofa_admin` cookie holding a random session ID, `HttpOnly`, `SameSite=Strict` and scoped to `Path=/admin`.
- Every page and action needs the session, whatever spelling of the path reaches it, except the sign-in form and the brand icon its tab shows. Every action is a `POST` that carries the session's CSRF token.
- The portal opens nothing under `/api/`. API clients still need their bearer token.
- Pages are sent with `Cache-Control: no-store` and a content security policy that allows scripts from the server only. htmx and the icon are served from the server; the portal loads nothing from other hosts.
- The output token appears only on the page answering "Reveal the output token", never in the page source by default. No other token reaches any page.
