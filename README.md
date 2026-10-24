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
  <img src="docs/images/android-tv-guide.webp" alt="The guide on Android TV: category chips, the focused channel's program beside its muted preview, and a grid of channels over the evening" width="800">
</p>

A self-hosted media center for a home network. One server keeps live TV, a DVR and your own movies and shows; one app watches all of it on Android TV, Google TV, phones, desktops, iOS, Apple TV and the web. No subscription or cloud account needed, and no reason to leave the sofa: it is Sofa King convenient.

- **Live TV in one lineup.** The server merges free streaming services, broadcasters' own free feeds, HDHomeRun tuners, M3U playlists and XMLTV guides into one numbered lineup, with fallbacks across services.
- **A DVR that skips the boring bits.** The server records programs and series from the guide into folders Plex, Jellyfin, Emby and Kodi scan, finds the commercial breaks, and the app plays the recordings, even while they record, and can skip the breaks.
- **Your own library.** Movies, shows and other videos from a folder, with details and artwork from their `.nfo` files or TMDB, Continue watching, Up next and Skip intro, converted on the fly for a device that cannot play the file.
- **Made for the couch.** The guide previews the focused channel muted and opens it full screen on the same stream, every screen works with a TV remote's D-pad, and the app reopens where you left it.
- **Plays well with others.** The same lineup goes to Plex as an HDHomeRun tuner and to M3U and XMLTV players.

<p align="center">
  <img src="docs/images/desktop-player.webp" alt="The player in the desktop app: a channel full screen with the info bar showing its number, name, program, rating, time and what is next, over the bar's buttons" width="800">
</p>

## Screenshots

Every platform's app against the same server, playing a showcase lineup of the Blender Foundation's open movies (Big Buck Bunny, Elephants Dream, Sintel, Tears of Steel and Caminandes, © Blender Foundation, under [Creative Commons Attribution](https://creativecommons.org/licenses/by/3.0/) licenses) with a made-up guide. [`screenshots.yml`](.github/workflows/screenshots.yml) takes them; [Screenshots](app/docs/development.md#screenshots) describes it.

**Android TV**

<p>
  <img src="docs/images/android-tv-player.webp" width="49%" alt="The channel list over a playing channel on Android TV">
  <img src="docs/images/android-tv-library.webp" width="49%" alt="The Library tab on Android TV: recently added movies and TV shows">
  <img src="docs/images/android-tv-show.webp" width="49%" alt="A show's page on Android TV: its seasons, its next episode to play and the episode list">
  <img src="docs/images/android-tv-movie.webp" width="49%" alt="A movie's page on Android TV: details, Play, Mark watched and the cast">
</p>

**Android phone**

<p>
  <img src="docs/images/android-phone-guide.webp" width="24%" alt="The guide on an Android phone">
  <img src="docs/images/android-phone-player.webp" width="24%" alt="The player on an Android phone with its info bar and buttons">
  <img src="docs/images/android-phone-library.webp" width="24%" alt="The Library tab on an Android phone">
  <img src="docs/images/android-phone-show.webp" width="24%" alt="A show's page on an Android phone">
</p>

**Android tablet**

<p>
  <img src="docs/images/android-tablet-guide.webp" width="32%" alt="The guide on an Android tablet: nothing has focus on a touch screen, so the preview waits for a tap">
  <img src="docs/images/android-tablet-player.webp" width="32%" alt="The player on an Android tablet with its info bar and buttons">
  <img src="docs/images/android-tablet-show.webp" width="32%" alt="A show's page on an Android tablet">
</p>

**Desktop (Linux)**

<p>
  <img src="docs/images/desktop-guide.webp" width="49%" alt="The guide in the desktop app with the focused channel's preview">
  <img src="docs/images/desktop-movie.webp" width="49%" alt="A movie's page in the desktop app">
</p>

**Web**

<p>
  <img src="docs/images/web-library.webp" width="49%" alt="The Library tab in a browser">
  <img src="docs/images/web-show.webp" width="49%" alt="A show's page in a browser">
</p>

**iPhone and iPad**

<p>
  <img src="docs/images/iphone-guide.webp" width="49%" alt="The guide on an iPhone">
  <img src="docs/images/iphone-player.webp" width="49%" alt="A channel full screen on an iPhone">
  <img src="docs/images/ipad-guide.webp" width="49%" alt="The guide on an iPad">
  <img src="docs/images/ipad-library.webp" width="49%" alt="The Library tab on an iPad">
</p>

**Apple TV**

<p>
  <img src="docs/images/apple-tv-guide.webp" width="49%" alt="The channel list on Apple TV, with search and what is on now and next">
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
