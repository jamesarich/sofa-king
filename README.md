# Sofa King

Live TV, Sofa King good: a self-hosted live TV server for a home network, and an app that watches it on Android TV, Google TV, phones, desktops, iPhone and the web.

- [`backend/`](backend/README.md): the server. It builds one channel lineup from free streaming services, M3U playlists and XMLTV guides, and serves it to the app, to Plex as an HDHomeRun tuner, and to M3U and XMLTV players. [Its documentation](backend/docs/README.md) covers deploying, the lineup and the API.
- [`app/`](app/README.md): the Kotlin Multiplatform client and its [documentation](app/docs/README.md).

The [`snapshot` prerelease](https://github.com/jamesarich/sofa-king/releases/tag/snapshot) has a build of the app for every platform from the latest `main`; the server image is `ghcr.io/jamesarich/sofa-king-backend:main`. [CONTRIBUTING](CONTRIBUTING.md) covers the app's build, CI and conventions, [the server's CONTRIBUTING](backend/CONTRIBUTING.md) the server's, and [SECURITY](SECURITY.md) how to report a vulnerability.

Licensed under the [GNU General Public License v3.0](LICENSE).
