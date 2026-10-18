# Sofa King server

The Sofa King server is a self-hosted live TV server for a home network. It builds one channel lineup from free streaming services, M3U playlists and XMLTV guides, and serves it to Plex as an HDHomeRun tuner, to M3U and XMLTV players, and to the Sofa King app on TVs, phones, desktops, iOS, Apple TV and the web. It also records programs from the guide and serves your own movies and shows to the app.

## Why

- **One container does the whole job.** Native clients for 17 free streaming (FAST) services, the free news feeds of a dozen broadcasters and HDHomeRun tuners sit beside an HDHomeRun emulation for Plex, so no separate playlist tool or tuner proxy is needed.
- **The service clients run on your server.** Hosted FAST playlists have been taken down after DMCA notices. Here the server talks to the services itself; only Samsung TV Plus and The Roku Channel take their channel lists from a published mirror.
- **One station per channel.** The same channel from several services becomes one station with the others as fallbacks. Channels whose names only roughly match are merged when their schedules agree, and kept apart otherwise.
- **Numbers stay put.** Every number the server assigns is written back to the lineup policy file, so a channel keeps its number through rebuilds and on a new server.
- **Failing sources step aside.** FFmpeg [probes sources](docs/source-health.md) on a schedule, quarantines the ones that fail, and playback fails over to the next source, from any service.
- **Plex stays in step.** The server saves Plex's channel maps itself and can add the tuners Plex is missing. With extra ports (`SOFA_HDHR_EXTRA_PORTS`), a large lineup spreads over several tuner devices, 250 channels each under a lineup policy, the size Plex can save.
- **Easy to run.** An admin portal works from a phone browser, with or without JavaScript. A host can update itself from the published image, with a database backup and a rollback when the new image is unhealthy.

## Features

- Native clients for 17 free streaming services, broadcasters' own free news feeds and HDHomeRun tuners, with their guides ([providers](docs/providers.md))
- M3U playlists and XMLTV guides, plain or gzip
- A [lineup policy](docs/lineup-policy.md) file that rebuilds the same lineup on any server, with a preview before any change
- Groups, stable numbers, logos, guide mapping and failover order, stored in SQLite
- [Source health](docs/source-health.md) probes, quarantine and automatic failover between sources
- HLS and MPEG-TS relayed through the server, and an MPEG-TS remux for Plex
- M3U and XMLTV outputs for players, and an HDHomeRun emulation for Plex Live TV
- [Recordings](docs/recordings.md) of one program or a whole series from the guide, in the folder layout Plex, Jellyfin, Emby and Kodi scan, with cleanup by keep count, age and free space, and commercial breaks found by Comskip
- [Media libraries](docs/libraries.md): movies, shows and other videos from a read-only folder, scanned with their `.nfo` files and artwork, with details from TMDB when a token is set, and household watch progress
- Library files and recordings played as they are, or copied or converted to HLS by FFmpeg for a player that cannot take the file, on the CPU or a GPU
- A logo cache, so every client loads logos from this server
- A JSON API whose live TV, recording and library types the app shares ([API](docs/api.md))
- An [admin portal](docs/admin.md) at `/admin` that shows what needs attention, sets up the sources and a starter lineup, edits channels, merges, groups and provider priority, manages recordings and series rules, adds media libraries, pairs devices with a code, and asks the host's updater for a new image
- A Docker image for `linux/amd64` and `linux/arm64`, with an SBOM and build provenance


## Clients

Only Plex and the Sofa King app have been tested. The others speak the same HDHomeRun or M3U and XMLTV interfaces and should work. Replace `SERVER_HOST` with the server's LAN address and `OUTPUT_TOKEN` with `SOFA_OUTPUT_TOKEN`.

| Client | Status | Connect with |
| --- | --- | --- |
| Plex Live TV & DVR | Tested | HDHomeRun at `SERVER_HOST:9193`, guide `http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN` ([Plex setup](docs/live-tv.md#plex-live-tv)) |
| [Sofa King app](../app/README.md) | Tested | Server `http://SERVER_HOST:9193` and the API token, or a [pairing code](docs/admin.md#devices); the web app [behind a proxy](docs/deploy.md#serve-the-web-app) |
| Jellyfin, Emby | Should work, untested | HDHomeRun tuner at `http://SERVER_HOST:9193`, or an M3U tuner with the playlist below; the XMLTV guide below |
| Channels DVR | Should work, untested | Custom channels source with the M3U playlist and XMLTV guide below |
| TiviMate, Kodi (IPTV Simple Client) | Should work, untested | The M3U playlist and XMLTV guide below |
| VLC | Should work, untested | Open the M3U playlist as a network stream; VLC shows no guide |

```text
http://SERVER_HOST:9193/output/playlist.m3u?token=OUTPUT_TOKEN
http://SERVER_HOST:9193/output/guide.xml?token=OUTPUT_TOKEN
```

The HDHomeRun routes admit only the addresses in `SOFA_HDHR_ALLOW`, so list every client that uses them. [Third-party players](docs/live-tv.md#third-party-players) and [Plex Live TV](docs/live-tv.md#plex-live-tv) have the details.

## Quick start

You need Docker and JDK 21, or access to the published image. [Getting started](docs/getting-started.md) builds and starts the server from `backend/`, signs in to the admin portal and walks to a playing channel. [Deploy with Docker](docs/deploy.md) covers the config folder, secrets, reverse proxies, the web app, the published image and automatic updates. To run without Docker, see [development](docs/development.md#run-without-docker).

## Documentation

The [documentation index](docs/README.md) lists every page. The most used are:

- [Getting started](docs/getting-started.md)
- [Deploy with Docker](docs/deploy.md)
- [Build a starter lineup](docs/starter-lineup.md)
- [Live TV: configuration, lineup and sources](docs/live-tv.md)
- [Recordings](docs/recordings.md)
- [Media libraries](docs/libraries.md)
- [Troubleshooting and FAQ](docs/troubleshooting.md)
- [Lineup policy](docs/lineup-policy.md)
- [HTTP API](docs/api.md)

## Contributing

[Development](docs/development.md) has the build and test commands, the smoke scripts and how to add a native provider. The repository's [CONTRIBUTING.md](../CONTRIBUTING.md) covers the commit and pull request conventions and CI.

## Security

The [security page](docs/security.md) describes the intended deployment, which token opens which routes, the request limits and the outbound address policy. Report a vulnerability as the repository's [SECURITY.md](../SECURITY.md) describes.

## Limitations

- Plex reaches the HDHomeRun emulation by address only. SSDP discovery is not implemented.
- The HDHomeRun remux of an HLS source carries one video stream and one audio stream.
- The native providers are unofficial clients of public service endpoints. They stop working when a service changes them.
- DRM channels are skipped by the native providers. M3U playlists are not filtered for DRM.
- [Recordings](docs/recordings.md#limitations) need the guide: there is no manual time-based recording.

## License

Licensed under the [GNU General Public License v3.0](../LICENSE).
