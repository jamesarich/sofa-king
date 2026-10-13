# Documentation

Sofa King is a live TV server and the app that watches it. Each part keeps its own pages:

- [Server documentation](../backend/docs/README.md): getting started, deploying with Docker, the lineup and its policy, providers and sources, Plex and the player outputs, the HTTP API, operations and troubleshooting.
- [App documentation](../app/docs/README.md): getting started on each platform, using the guide and the player, troubleshooting, security, architecture and development.
- [Contract](../contract/README.md): the wire types the server and the app share, and the rules for changing them.

[CONTRIBUTING](../CONTRIBUTING.md) covers the shared conventions and CI, and [SECURITY](../SECURITY.md) how to report a vulnerability. The server has its own [CONTRIBUTING](../backend/CONTRIBUTING.md) and [SECURITY](../backend/SECURITY.md) for its build, tokens and routes; the app's build is in its [development](../app/docs/development.md) page and its security notes in its [security](../app/docs/security.md) page.

## Glossary

Terms both parts use. Each part's docs index defines the rest.

| Term | Meaning |
| --- | --- |
| Server | The Sofa King server in `backend/`. It builds the lineup, holds the guide data and relays every stream |
| App | The Sofa King app in `app/`, on Android TV, Google TV, phones, desktops, iPhone and the web |
| Lineup | Every lineup entry the server lists, in order. The app, the M3U and XMLTV outputs and Plex all serve the same lineup |
| Channel, lineup entry | One entry in the lineup: its number, name, group, logo, guide mapping and sources, under a stable ID. The API's channel ID is the lineup entry ID |
| Group, tag | A channel's lineup group, such as News. The API sends it as the channel's tag, and the app shows each as a filter chip |
| Guide | In the app, the grid of channels and their programs over 24 hours. In the server's docs, an XMLTV source of programs that the server imports, which the app's docs call a guide source |
| Program | One scheduled show. XMLTV and the API call it a programme |
| Playback session | The server's lease on one stream, opened as a preview or a watch. The app holds one at a time and promotes its preview to watch when the channel goes full screen |
