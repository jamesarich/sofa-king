<p align="center">
  <img src="backend/server/src/main/resources/admin/icon.svg" alt="Sofa King logo: a crown on a sofa" width="120">
</p>

<h1 align="center">Sofa King</h1>

<p align="center"><strong>Your own media center. Sofa King good.</strong></p>

<p align="center">
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-FFB547?labelColor=14111F"></a>
  <a href="app/README.md"><img alt="App: Kotlin Multiplatform" src="https://img.shields.io/badge/app-Kotlin_Multiplatform-C9B8FF?labelColor=14111F"></a>
  <a href="backend/README.md"><img alt="Server: Ktor in Docker" src="https://img.shields.io/badge/server-Ktor_in_Docker-C9B8FF?labelColor=14111F"></a>
  <a href="app/README.md#platforms"><img alt="Platforms: Android TV, Android, desktop, iOS, Apple TV, web" src="https://img.shields.io/badge/platforms-Android_TV_·_Android_·_desktop_·_iOS_·_Apple_TV_·_web-C9B8FF?labelColor=14111F"></a>
</p>

<p align="center">
  <img src="docs/images/guide.png" alt="The guide in the desktop app: category chips, the focused channel's program and its muted preview, and a grid of channels over the afternoon" width="800">
</p>

A self-hosted media center for a home network. One server keeps live TV, a DVR and your own movies and shows; one app watches all of it on Android TV, Google TV, phones, desktops, iOS, Apple TV and the web. No subscription or cloud account needed, and no reason to leave the sofa: it is Sofa King convenient.

- **Live TV in one lineup.** The server merges free streaming services, broadcasters' own free feeds, HDHomeRun tuners, M3U playlists and XMLTV guides into one numbered lineup, with fallbacks across services.
- **A DVR that skips the boring bits.** The server records programs and series from the guide into folders Plex, Jellyfin, Emby and Kodi scan, finds the commercial breaks, and the app plays the recordings, even while they record, and can skip the breaks.
- **Your own library.** Movies, shows and other videos from a folder, with details and artwork from their `.nfo` files or TMDB, Continue watching, Up next and Skip intro, converted on the fly for a device that cannot play the file.
- **Made for the couch.** The guide previews the focused channel muted and opens it full screen on the same stream, every screen works with a TV remote's D-pad, and the app reopens where you left it.
- **Plays well with others.** The same lineup goes to Plex as an HDHomeRun tuner and to M3U and XMLTV players.

<p align="center">
  <img src="docs/images/player.png" alt="The player in the desktop app: a channel full screen with the info bar showing its number, name, program, rating, time and what is next" width="800">
</p>

## Get it

- **Server:** the image `ghcr.io/jamesarich/sofa-king:main`, published from `main`. [Getting started](backend/docs/getting-started.md) takes a Docker host to a playing channel.
- **App:** the [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot), built from the latest `main` for Android, Linux, macOS, Windows and the web. [Getting started](app/docs/getting-started.md) installs it and connects it to the server.

The server image is public on GitHub Container Registry; anyone can pull it or build it from this repository. Neither part has a versioned release yet. Each README lists what is missing: [server](backend/README.md#limitations), [app](app/README.md#limitations).

| Part | What it is |
| --- | --- |
| [`backend/`](backend/README.md) | The server. Its [documentation](backend/docs/README.md) covers deploying, the lineup and the API |
| [`app/`](app/README.md) | The Kotlin Multiplatform client and its [documentation](app/docs/README.md) |
| [`contract/`](contract/README.md) | The wire types the server and the app share |

The [documentation index](docs/README.md) links every page and the shared glossary. [CONTRIBUTING](CONTRIBUTING.md) covers the repository's layout, the conventions both parts share and CI, and [SECURITY](SECURITY.md) covers reporting a vulnerability.

Licensed under the [GNU General Public License v3.0](LICENSE). Now put your feet up; it is Sofa King relaxing.
