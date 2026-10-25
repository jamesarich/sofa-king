# Server documentation

The Sofa King server keeps live TV, recordings and media libraries for the app and other players. These pages are Sofa King thorough; start with [getting started](getting-started.md).

## Guides

| Page | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | From `docker compose up` to a channel playing in Plex, the Sofa King app or an M3U player |
| [Deploy with Docker](deploy.md) | The config folder, running as your own user, secrets, reverse proxies, serving the web app, the published image and its provenance, and automatic updates |
| [Build a starter lineup](starter-lineup.md) | From an empty server to a grouped lineup of the free services with fallbacks across them, plus your local channels, and a lineup built by hand without a policy |
| [Admin portal](admin.md) | The web pages at `/admin`: the home page and setup, channel edits, merges, required stations, groups, the sources and provider priority, devices and pairing, Plex, recordings, media libraries, the runtime and effective settings, and the System pages for updates, jobs, events and a support snapshot |
| [Operations](operations.md) | State and backups, restore, upgrades and rollback, monitoring, logs, native provider failures and token rotation |
| [Use with Sonarr, Radarr and Bazarr](integrations.md) | The media layout the three share, path mapping, hook tokens, the Webhook and Emby / Jellyfin connections that queue a scan when a file changes, and Bazarr's subtitles |
| [Troubleshooting and FAQ](troubleshooting.md) | Symptoms and fixes for startup, tokens, sources, the guide, players, Plex, recordings, media libraries and reverse proxies, and common questions |

## Reference

| Page | What it covers |
| --- | --- |
| [Server configuration and live TV](live-tv.md) | Every environment variable, source files, source identity, the lineup, logos, player outputs, Plex Live TV and upstream addresses |
| [Recordings](recordings.md) | Recording a program or a series, the schedule, what happens while a recording runs, playing recordings, commercial breaks, cleanup and the recording states |
| [Media libraries](libraries.md) | The media folder, how to name movies, shows and other videos, `.nfo` files and artwork, details from TMDB, scans, intros, playback and hardware transcoding |
| [Virtual channels](virtual-channels.md) | Channels the server plays from the libraries on a schedule: slots, shuffles, time slots, filler, the guide and how they stream |
| [Native providers](providers.md) | The built-in clients for free streaming services, their options and their failure modes |
| [Lineup policy](lineup-policy.md) | The policy file that rebuilds the whole lineup, its fields, runs, required stations, findings, API and the generator |
| [Source health](source-health.md) | Probes, failure classes, quarantine and recovery, source selection, and manual disables |
| [HTTP API](api.md) | Endpoints for clients, recordings, media libraries and library playback, the player outputs, the HDHomeRun routes, the admin API and portal routes, errors and the types the app shares |
| [Security](security.md) | The intended deployment, which credential opens which routes, request limits, outbound requests and a hardening checklist |
| [Architecture](architecture.md) | Modules, data flow, storage, guide imports, playback, logos, the HDHomeRun remux and the logic behind each subsystem |

## Contributing

| Page | What it covers |
| --- | --- |
| [Development](development.md) | Running without Docker, build and test, code conventions, the smoke scripts and adding a native provider |
| [Dependencies](dependencies.md) | Pinned versions, Renovate and how to change versions |

The server's [README](../README.md) introduces it. The repository's [CONTRIBUTING](../../CONTRIBUTING.md) covers the conventions the server shares with the app and CI, and its [SECURITY](../../SECURITY.md) how to report a vulnerability. The [app's documentation](../../app/docs/README.md) covers the client that reads the API.

## Glossary

The terms the server shares with the app (lineup, channel or lineup entry, group, program, playback session) are in the [repository glossary](../../docs/README.md#glossary). These are the server's own:

| Term | Meaning |
| --- | --- |
| Provider | One entry in `sources.json`: an M3U playlist, or a native client for one free streaming service. Each has an ID, such as `pluto` |
| Guide, guide source | An XMLTV source of programs that the server imports. A provider can bring its own guide, and `sources.json` can list more. It is not the app's guide grid. |
| Candidate | A channel as one provider lists it, before it is in the lineup |
| Source | One stream URL a provider lists for a channel. Health is tracked per source, and a channel can have several, from any providers, in failover order. The API calls sources `offerings` |
| Station | One channel as the viewer sees it. The lineup policy builds one station from all the candidates that share a station key, a normalized form of the name, and serves it as one lineup entry |
| Lineup policy | The `policy.json` file that, with `sources.json`, decides the whole lineup |
| Series rule | A title the DVR records every airing of, on one channel or any |
| Recordings folder, media folder | `SOFA_RECORDINGS_DIR`, where the DVR writes its recordings, and `SOFA_MEDIA_DIR`, the read-only folder media libraries are added from |
| Device, tuner | An HDHomeRun device is what Plex adds, one per server port. Each device has `SOFA_HDHR_TUNERS` tuners, the streams it may play at once |
