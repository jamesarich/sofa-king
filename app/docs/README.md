# App documentation

## Guides

| Page | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | From a running Sofa King server to a channel playing on a TV, a desktop, a phone or a browser, including serving the web app |
| [Using Sofa King](usage.md) | The guide, the channels it lists, previews, the player, the remote and keyboard controls, captions, the Apple TV app and the settings |
| [Troubleshooting and FAQ](troubleshooting.md) | Symptoms and fixes for connecting, the lineup and guide, playback, captions and each platform, and common questions |

## Reference

| Page | What it covers |
| --- | --- |
| [Architecture](architecture.md) | Modules, components, data flow, storage, launch, the guide, playback on each platform and the server interface |
| [Security](security.md) | What the app stores, where it sends the API token, and cleartext HTTP |

## Contributing

| Page | What it covers |
| --- | --- |
| [Development](development.md) | Build and test, running each platform, the TV checklist, CI and snapshot builds |
| [Dependencies](dependencies.md) | Pinned versions, locking and how to change versions |

The app's [README](../README.md) introduces it. The repository's [CONTRIBUTING](../../CONTRIBUTING.md) covers the conventions the app shares with the server, and its [SECURITY](../../SECURITY.md) how to report a vulnerability. The [server's documentation](../../backend/docs/README.md) covers the lineup, sources and the API the app reads.

## Glossary

The [repository glossary](../../docs/README.md#glossary) defines the terms the app shares with the server: server, lineup, channel, tag, guide, program and playback session. These terms are the app's own:

| Term | Meaning |
| --- | --- |
| Category | A tag shown as a filter chip in the guide. A channel without a tag is under Other |
| Guide source | The server's XMLTV source of programs for a channel, which the server's docs call a guide |
| Preview | The muted picture of the focused channel in the guide |
| Info bar | The overlay in the player with the channel and its current program |
| Channel list | The list of channels the player opens over the picture, from the info bar, with Recent, Favorites, filter and All chips and the current channel's buttons |
| Stream card | The card the player opens on Info held or Stream info in the channel list, with the audio tracks, stream details and the server's source |
| Live edge | The newest part of a live stream. Resuming after a long pause, or falling behind, returns to it |
| Caption timing | How far captions are shifted against the picture, saved per channel |
| Snapshot | A test build from `main`, published as the `snapshot` prerelease |
