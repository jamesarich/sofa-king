# Server documentation

## Guides

| Page | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | From `docker compose up` to a channel playing in Plex, the Sofa King app or an M3U player |
| [Deploy with Docker](deploy.md) | The config folder, running as your own user, secrets, reverse proxies, serving the web app, the published image and its provenance, and automatic updates |
| [Build a starter lineup](starter-lineup.md) | From an empty server to a grouped lineup of the free services with fallbacks across them, plus your local channels, and a lineup built by hand without a policy |
| [Admin portal](admin.md) | The web pages at `/admin`: the overview, channel edits, merges, required stations, groups and provider priority, Plex and the effective settings |
| [Operations](operations.md) | State and backups, restore, upgrades and rollback, monitoring, logs, native provider failures and token rotation |
| [Troubleshooting and FAQ](troubleshooting.md) | Symptoms and fixes for startup, tokens, sources, the guide, players, Plex and reverse proxies, and common questions |

## Reference

| Page | What it covers |
| --- | --- |
| [Live TV](live-tv.md) | Every environment variable, source files, source identity, the lineup, logos, player outputs, Plex Live TV and upstream addresses |
| [Native providers](providers.md) | The built-in clients for free streaming services, their options and their failure modes |
| [Lineup policy](lineup-policy.md) | The policy file that rebuilds the whole lineup, its fields, runs, required stations, findings, API and the generator |
| [Source health](source-health.md) | Probes, failure classes, quarantine and recovery, source selection, and manual disables |
| [HTTP API](api.md) | Endpoints for clients, the player outputs, the HDHomeRun routes, the admin API and portal routes, errors and the types the app shares |
| [Security](security.md) | The intended deployment, which credential opens which routes, request limits, outbound requests and a hardening checklist |
| [Architecture](architecture.md) | Modules, data flow, storage, guide imports, playback, logos, the HDHomeRun remux and the logic behind each subsystem |

## Contributing

| Page | What it covers |
| --- | --- |
| [Development](development.md) | Running without Docker, build and test, code conventions, the smoke scripts and adding a native provider |
| [Dependencies](dependencies.md) | Pinned versions, Renovate, verification metadata and how to change versions |

The server's [README](../README.md) introduces it. The repository's [CONTRIBUTING](../../CONTRIBUTING.md) covers the conventions the server shares with the app and CI, and its [SECURITY](../../SECURITY.md) how to report a vulnerability. The [app's documentation](../../app/docs/README.md) covers the client that reads the API.

## Glossary

The terms the server shares with the app (lineup, channel or lineup entry, group, program, playback session) are in the [repository glossary](../../docs/README.md#glossary). These are the server's own:

| Term | Meaning |
| --- | --- |
| Provider | One entry in `sources.json`: an M3U playlist, or a native client for one free streaming service. Each has an ID, such as `pluto` |
| Guide, guide source | An XMLTV source of programs that the server imports. A provider can bring its own guide, and `sources.json` can list more. It is not the app's guide grid. The API's guide counts use the XMLTV field name `programmes` |
| Candidate | A channel as one provider lists it, before it is in the lineup |
| Source | One stream URL a provider lists for a channel. Health is tracked per source, and a channel can have several, from any providers, in failover order. The API calls sources `offerings` |
| Station | One channel as the viewer sees it. The lineup policy builds one station from all the candidates that share a station key, a normalized form of the name, and serves it as one lineup entry |
| Lineup policy | The `policy.json` file that, with `sources.json`, decides the whole lineup |
| Device, tuner | An HDHomeRun device is what Plex adds, one per server port. Each device has `SOFA_HDHR_TUNERS` tuners, the streams it may play at once |
