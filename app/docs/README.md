# Documentation

## Guides

| Page | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | From a running Sofa King server to a channel playing on a TV, a desktop, a phone or a browser |
| [Using Sofa King](usage.md) | The guide, previews, the player, the remote and keyboard controls, captions and the settings |
| [Troubleshooting and FAQ](troubleshooting.md) | Symptoms and fixes for connecting, the lineup and guide, playback, captions and each platform, and common questions |

## Reference

| Page | What it covers |
| --- | --- |
| [Architecture](architecture.md) | Modules, components, data flow, storage, launch, the guide, playback on each platform and the server interface |

## Contributing

| Page | What it covers |
| --- | --- |
| [Development](development.md) | Build and test, running each platform, the TV checklist, CI and snapshot builds |
| [Dependencies](dependencies.md) | Pinned versions, locking and how to change versions |

The app's [README](../README.md) introduces it. [CONTRIBUTING](../../CONTRIBUTING.md) and [SECURITY](../../SECURITY.md) are at the repository root. The [server's documentation](../../backend/docs/README.md) covers the lineup, sources and the API the app reads.

## Glossary

Terms the app shares with the server are also in the [repository glossary](../../docs/README.md#glossary).

| Term | Meaning |
| --- | --- |
| Server | The [Sofa King server](../../backend/README.md). It builds the lineup, holds the guide and relays every stream |
| Lineup | The channels the server lists. The app shows the playable ones with a numeric display number, in number order |
| Channel | One lineup entry: its number, name, logo, tags and stream |
| Tag, category | The channel's lineup group, such as News, which the server sends as its tag. Each tag becomes a filter chip in the guide |
| Guide | The grid of channels and their programs over 24 hours |
| Program | One scheduled show in the guide. The server's API calls it a programme |
| Preview | The muted picture of the focused channel in the guide |
| Playback session | The server's lease on one stream. The app holds one at a time, opened as a preview and promoted to watch when the channel goes full screen |
| Live edge | The newest part of a live stream. Resuming after a long pause, or falling behind, returns to it |
| Caption timing | How far captions are shifted against the picture, saved per channel |
| Snapshot | A test build from `main`, published as the `snapshot` prerelease |
