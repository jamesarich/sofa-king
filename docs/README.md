# Documentation

## Guides

| Page | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | From `docker compose up` to a channel playing in Plex, the Sofa King app or an M3U player |
| [Deploy with Docker](deploy.md) | The quick start, the config folder, running as your own user, secrets, reverse proxies, the published image and its attestation, and automatic updates |
| [Build a starter lineup](starter-lineup.md) | From an empty server to a grouped lineup of the free services with fallbacks across them, plus your local channels |
| [Admin portal](admin.md) | The web pages at `/admin`: the overview, channel edits, merges, required stations, groups and provider priority, Plex and the effective settings |
| [Operations](operations.md) | State and backups, restore, upgrades and rollback, monitoring, logs, native provider failures and token rotation |
| [Troubleshooting and FAQ](troubleshooting.md) | Symptoms and fixes for startup, tokens, sources, the guide, players, Plex and reverse proxies, and common questions |

## Reference

| Page | What it covers |
| --- | --- |
| [Live TV](live-tv.md) | Every environment variable, source files, source identity, the lineup, logos, player outputs, Plex Live TV and LAN access |
| [Native providers](providers.md) | The built-in clients for free streaming services, their options and their failure modes |
| [Lineup policy](lineup-policy.md) | The policy file that rebuilds the whole lineup, its fields, runs, required stations, findings, API and the generator |
| [Source health](source-health.md) | Probes, failure classes, quarantine and recovery, and manual disables |
| [HTTP API](api.md) | Endpoints for clients, the player outputs, the HDHomeRun routes, the admin API and portal routes, errors and the Kotlin client |
| [Architecture](architecture.md) | Modules, data flow, storage, playback and the logic behind each subsystem |

## Contributing

| Page | What it covers |
| --- | --- |
| [Development](development.md) | Running without Docker, build, test and the smoke scripts |
| [Dependencies](dependencies.md) | Pinned versions, Renovate, verification metadata and how to change versions |

The top-level [README](../README.md) introduces the server. [CONTRIBUTING](../CONTRIBUTING.md) and [SECURITY](../SECURITY.md) are at the repository root.

## Glossary

| Term | Meaning |
| --- | --- |
| Provider | One entry in `sources.json`: an M3U playlist, or a native client for one free streaming service. Each has an ID, such as `pluto` |
| Guide | An XMLTV source of programs. A provider can bring its own guide, and `sources.json` can list more |
| Program | One scheduled show in a guide. XMLTV calls it a `<programme>`, and the API's guide counts use the field name `programmes` |
| Candidate | A channel as one provider lists it, before it is in the lineup |
| Source | One stream URL a provider lists for a channel. Health is tracked per source, and a channel can have several, from any providers, in failover order. The API calls sources `offerings` |
| Station | One channel as the viewer sees it. The lineup policy builds one station from all the candidates that share a station key, a normalized form of the name |
| Lineup entry, channel | What clients see: a station's number, name, group, logo, guide mapping and sources, under a stable ID. The API's channel ID is the lineup entry ID |
| Lineup | Every lineup entry, in order. The app, the M3U and XMLTV outputs and Plex all serve the same lineup |
| Lineup policy | The `policy.json` file that, with `sources.json`, decides the whole lineup |
| Device, tuner | An HDHomeRun device is what Plex adds, one per server port. Each device has `SOFA_HDHR_TUNERS` tuners, the streams it may play at once |
