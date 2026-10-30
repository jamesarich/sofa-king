# Sofa King documentation

Sofa King is a self-hosted media center: a server that keeps live TV, recordings and media libraries, and the app that watches all three. The same pages are on the [documentation site](https://jamesarich.github.io/sofa-king/), with search and the [API reference](https://jamesarich.github.io/sofa-king/api/index.html).

| Where | What it covers |
| --- | --- |
| [Server documentation](../backend/docs/README.md) | Deploying, the lineup, recordings, libraries, the admin portal, the HTTP API, security, architecture and development |
| [App documentation](../app/docs/README.md) | Installing and using the app on each platform, security, architecture and development |
| [Contract](../contract/README.md) | The wire types the server and the app share, and the rules for changing them |
| [How Sofa King compares](comparison.md) | Plex, Jellyfin, Emby and Channels DVR side by side, and what Sofa King does not do |
| [Coming from Plex or Jellyfin](migrating.md) | Keeping your folders and your other server while the household switches |
| [CONTRIBUTING](../CONTRIBUTING.md) | The repository's layout, conventions and CI |
| [SECURITY](../SECURITY.md), [SUPPORT](../SUPPORT.md) | Reporting a vulnerability, and where to get help |

## Glossary

Terms both parts use. Each part's docs index defines the rest.

| Term | Meaning |
| --- | --- |
| Server | The Sofa King server in `backend/`. It builds the lineup, holds the guide data, relays every stream, records programs and serves the media libraries |
| App | The Sofa King app in `app/`, on Android TV, Google TV, phones, desktops, iOS, Apple TV and the web |
| Lineup | Every lineup entry the server lists, in order. The app, the M3U and XMLTV outputs and Plex all serve the same lineup |
| Channel, lineup entry | One entry in the lineup: its number, name, group, logo, guide mapping and sources, under a stable ID. The API's channel ID is the lineup entry ID |
| Group, tag | A channel's lineup group, such as News. The API sends it in the channel's `tags`, and the app shows each as a filter chip |
| Guide | In the app, the grid of channels and their programs over 24 hours. In the server's docs, an XMLTV source of programs that the server imports (a guide source) |
| Program | One scheduled show. XMLTV calls it a programme |
| Playback session | The server's lease on one stream, under which the app holds one at a time. A live session opens as a preview or a watch, and the app promotes its preview to watch when the channel goes full screen. A library playback session, under `/api/v1/vod-sessions`, plays one library item or recording as the file is or with its streams copied or converted to HLS |
| Recording | One program the server records from a channel into the recordings folder, scheduled by hand or by a series rule |
| Media library | A folder of movies, TV shows or other videos under the server's media folder or its recordings folder, which the server scans. Its movies, shows, seasons, episodes and videos are library items |
| Watchlist | The household's list of library items to watch and guide titles to watch for, kept on the server, with the next airing of each title |
