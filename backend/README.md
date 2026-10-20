# Sofa King server

The Sofa King server is the heart of a self-hosted media center for a home network, and it is Sofa King busy. It keeps three things in one container:

- **Live TV.** One channel lineup built from free streaming services, broadcasters' free news feeds, HDHomeRun tuners, M3U playlists and XMLTV guides, served to the Sofa King app, to Plex as an HDHomeRun tuner, and to M3U and XMLTV players.
- **Recordings.** A DVR that records programs, time windows and series from the guide, finds their commercial breaks and keeps them in the folder layout media servers scan.
- **Media libraries.** Your own movies, shows and other videos from a read-only folder, with details and artwork, watch progress, and playback converted on the fly for a device that cannot take the file.

The [Sofa King app](../app/README.md) plays all three on TVs, phones, desktops, iOS, Apple TV and the web.

## Why

- **One container does the whole job.** Native clients for 17 free streaming (FAST) services, the free news feeds of a dozen broadcasters and HDHomeRun tuners share one container with an HDHomeRun emulation for Plex, a DVR and a library server, so there is no separate playlist tool, tuner proxy or recorder to run.
- **The service clients run on your server.** Hosted FAST playlists have been taken down after DMCA notices. Here the server talks to the services itself; only Samsung TV Plus and The Roku Channel take their channel lists from a published mirror.
- **One station per channel.** The same channel from several services becomes one station with the others as fallbacks. Channels whose names only roughly match are merged when their schedules agree, and kept apart otherwise.
- **Numbers stay put.** Every number the server assigns is written back to the lineup policy file, so a channel keeps its number through rebuilds and on a new server.
- **Failing sources step aside.** FFmpeg [probes sources](docs/source-health.md) on a schedule, quarantines the ones that fail, and playback fails over to the next source, from any service.
- **Plex stays in step.** The server saves Plex's channel maps itself and can add the tuners Plex is missing. With extra ports (`SOFA_HDHR_EXTRA_PORTS`), a large lineup spreads over several tuner devices, 250 channels each under a lineup policy, the size Plex can save.
- **Recordings any media server reads.** Files are named the way Plex, Jellyfin, Emby and Kodi scan them, Comskip marks the commercial breaks, and cleanup keeps the folder within its keep counts, age and free space.
- **Libraries that play on anything.** A file a device can take plays as it is; anything else is copied or converted to HLS by FFmpeg as it plays, on the CPU or a GPU, with HDR tone-mapped for an SDR screen.
- **Sofa King easy to run.** An admin portal works from a phone browser, with or without JavaScript. A host can update itself from the published image, with a database backup and a rollback when the new image is unhealthy.

## Features

Live TV:

- Native clients for 17 free streaming services, broadcasters' own free news feeds and HDHomeRun tuners, with their guides ([providers](docs/providers.md))
- M3U playlists and XMLTV guides, plain or gzip
- A [lineup policy](docs/lineup-policy.md) file that rebuilds the same lineup on any server, with a preview before any change
- Groups, stable numbers, logos, guide mapping and failover order, stored in SQLite
- [Source health](docs/source-health.md) probes, quarantine and automatic failover between sources
- HLS and MPEG-TS relayed through the server, and an MPEG-TS remux for Plex
- M3U and XMLTV outputs for players, and an HDHomeRun emulation for Plex Live TV
- A logo cache, so every client loads logos from this server

Recordings and media libraries:

- [Recordings](docs/recordings.md) of one program, a time window or a whole series, in the folder layout Plex, Jellyfin, Emby and Kodi scan, with cleanup by keep count, age and free space, and commercial breaks found by Comskip
- [Media libraries](docs/libraries.md): movies, shows and other videos from a read-only folder, scanned with their `.nfo` files and artwork, with details from TMDB when a token is set, and household watch progress
- Library files and recordings played as they are, or copied or converted to HLS by FFmpeg for a player that cannot take the file, on the CPU or a GPU

Running it:

- A JSON API whose live TV, recording and library types the app shares ([API](docs/api.md))
- An [admin portal](docs/admin.md) at `/admin` that shows what needs attention, sets up the sources and a starter lineup, edits channels, merges, groups and provider priority, manages recordings and series rules, adds media libraries, pairs devices with a code, and asks the host's updater for a new image
- A Docker image for `linux/amd64` and `linux/arm64`, with an SBOM and build provenance

## Clients

Only Plex and the Sofa King app have been tested. The others speak the same HDHomeRun or M3U and XMLTV interfaces and should work. Replace `SERVER_HOST` with the server's LAN address and `OUTPUT_TOKEN` with the output token, which the admin portal's Plex page reveals.

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

The HDHomeRun routes admit only the addresses allowed on the portal's Plex page or in `SOFA_HDHR_ALLOW`; the page lists the addresses it refused, with Allow. [Third-party players](docs/live-tv.md#third-party-players) and [Plex Live TV](docs/live-tv.md#plex-live-tv) have the details.

## Quick start

You need Docker with Compose 2.24 or later on a Linux host, `amd64` or `arm64`. No checkout and no build: Compose runs the published image.

```sh
mkdir -p sofa-king/config sofa-king/recordings
cd sofa-king
curl -fsSLO https://raw.githubusercontent.com/jamesarich/sofa-king/main/backend/deploy/compose.yaml
docker compose up -d
```

Then open `http://SERVER_HOST:9193/admin`. [Getting started](docs/getting-started.md) signs in to the admin portal and walks to a playing channel. [Deploy with Docker](docs/deploy.md) covers the config folder, secrets, reverse proxies, the web app, your own build and automatic updates. To run without Docker, see [development](docs/development.md#run-without-docker).

## Documentation

The [documentation index](docs/README.md) lists every page. The most used are:

- [Getting started](docs/getting-started.md)
- [Deploy with Docker](docs/deploy.md)
- [Build a starter lineup](docs/starter-lineup.md)
- [Server configuration and live TV](docs/live-tv.md)
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
- [Series recordings](docs/recordings.md#limitations) need the guide; a channel without one records only as a time window.

## License

Licensed under the [GNU General Public License v3.0](../LICENSE).
