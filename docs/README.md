# Sofa King documentation

Sofa King is a live TV server, with recordings and media libraries, and the app that watches it. Each part keeps its own pages:

- [Server documentation](../backend/docs/README.md): [getting started](../backend/docs/getting-started.md), [deploying with Docker](../backend/docs/deploy.md), [building a starter lineup](../backend/docs/starter-lineup.md), the [admin portal](../backend/docs/admin.md), [operations](../backend/docs/operations.md), [troubleshooting](../backend/docs/troubleshooting.md), [live TV](../backend/docs/live-tv.md) (the settings, sources, lineup, player outputs and Plex), [recordings](../backend/docs/recordings.md), [media libraries](../backend/docs/libraries.md), [native providers](../backend/docs/providers.md), the [lineup policy](../backend/docs/lineup-policy.md), [source health](../backend/docs/source-health.md), the [HTTP API](../backend/docs/api.md), [security](../backend/docs/security.md), [architecture](../backend/docs/architecture.md), [development](../backend/docs/development.md) and [dependencies](../backend/docs/dependencies.md).
- [App documentation](../app/docs/README.md): [getting started](../app/docs/getting-started.md) on each platform, [using the guide, the player, the library and recordings](../app/docs/usage.md), [troubleshooting](../app/docs/troubleshooting.md), [security](../app/docs/security.md), [architecture](../app/docs/architecture.md), [development](../app/docs/development.md) and [dependencies](../app/docs/dependencies.md).
- [Contract](../contract/README.md): the wire types the server and the app share, and the rules for changing them.

[CONTRIBUTING](../CONTRIBUTING.md) covers the repository's layout, the shared conventions and CI, and [SECURITY](../SECURITY.md) how to report a vulnerability. Each part keeps its build in its development page ([app](../app/docs/development.md), [server](../backend/docs/development.md)) and its security notes in its security page ([app](../app/docs/security.md), [server](../backend/docs/security.md)).

## Glossary

Terms both parts use. Each part's docs index defines the rest.

| Term | Meaning |
| --- | --- |
| Server | The Sofa King server in `backend/`. It builds the lineup, holds the guide data, relays every stream, records programs and serves the media libraries |
| App | The Sofa King app in `app/`, on Android TV, Google TV, phones, desktops, iOS, Apple TV and the web |
| Lineup | Every lineup entry the server lists, in order. The app, the M3U and XMLTV outputs and Plex all serve the same lineup |
| Channel, lineup entry | One entry in the lineup: its number, name, group, logo, guide mapping and sources, under a stable ID. The API's channel ID is the lineup entry ID |
| Group, tag | A channel's lineup group, such as News. The API sends it in the channel's `tags`, and the app shows each as a filter chip |
| Guide | In the app, the grid of channels and their programs over 24 hours. In the server's docs, an XMLTV source of programs that the server imports, which the app's docs call a guide source |
| Program | One scheduled show. XMLTV and the API call it a programme |
| Playback session | The server's lease on one live stream, opened as a preview or a watch. The app holds one at a time and promotes its preview to watch when the channel goes full screen |
| Recording | One program the server records from a channel into the recordings folder, scheduled by hand or by a series rule |
| Media library | A folder of movies, TV shows or other videos under the server's media folder or its recordings folder, which the server scans. Its movies, shows, seasons, episodes and videos are library items |
| Library playback session | The server's lease on playing one library item or recording, under `/api/v1/vod-sessions`: the file as it is, or its streams copied or converted to HLS. While it plays, it is the app's one session |
