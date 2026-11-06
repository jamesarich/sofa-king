# How Sofa King compares

This page sets Sofa King next to the servers people usually come from and says what it does not do. The [README](../README.md) lists what it includes; every feature is in the one free build, with no paid tier. Prices are the vendors' own, with the page and the date they were read; check the vendor before you decide.

## Next to the usual servers

| | Sofa King | Plex | Jellyfin | Emby | Channels DVR |
| --- | --- | --- | --- | --- | --- |
| Price | Free | Free tier; Plex Pass lifetime $749.99 since July 1, 2026 ([Plex, May 18, 2026](https://forums.plex.tv/t/new-lifetime-plex-pass-pricing/938910)) | Free | Emby Premiere unlocks the full feature set, monthly, yearly or for life ([options](https://emby.media/support/articles/Premiere-Membership-Options.html)) | $8 a month or $80 a year ([Channels, read October 25, 2026](https://getchannels.com/docs/getting-started/quick-start-guide/subscription/)) |
| Account | None | A Plex account | None | Emby Connect optional | A Channels account |
| Source code | Open, GPL-3.0 | Closed | Open, GPL-2.0 | Closed | Closed |
| Merges sources into one lineup, with failover | Yes, matched by schedule, and the next source takes over when one fails | No | No | No | Stacks duplicate channels and tries the sources in their priority order ([Channels settings](https://getchannels.com/docs/), [Channels community, read November 6, 2026](https://community.getchannels.com/t/source-failover-based-on-signal-quality/19438)); no switch on signal quality |
| Own apps for TV, phone, desktop and web | Yes: Compose for Android TV, Android and desktop; SwiftUI for iPhone, iPad and Apple TV on a shared Kotlin core; a TypeScript and hls.js web client | Yes | Clients from several projects | Yes | Yes |
| Library | Movies, TV, music | Movies, TV, music, photos | Movies, TV, music, books, photos | Movies, TV, music, books, photos | Imported videos |

## What Sofa King does not do

Say no early rather than after an install:

- **No relay for watching away from home.** The server has no cloud service and opens no port. Reach it from outside through your own VPN, as [remote access](../backend/docs/deploy.md#remote-access) describes. Channels DVR documents port forwarding, an Away From Home sign-in with a Channels account and Tailscale ([Channels, read November 6, 2026](https://getchannels.com/docs/articles/how-to/stream-remotely/)).
- **No Chromecast or DLNA.** The apps play on the device they run on; the server's UPnP description only lets Plex find it as an HDHomeRun tuner.
- **No subtitle download.** Subtitles come from inside the file and from files beside it.
- **No chapters and no collections** in the libraries.
- **No plugins.** Features are in the server and the apps.
- **No per-user statistics.** Watch progress and favorites are the household's.
- **No hardware decode.** A GPU encodes converted library files ([hardware transcoding](../backend/docs/libraries.md#hardware-transcoding)); decoding and scaling stay in software.
- **No photos or books.** Libraries hold movies, TV shows, other videos and music.
- **No offline downloads** to a phone or tablet.
- **No user accounts or profiles.** The household shares one set of favorites and watch progress; kids mode limits one device.
- **No Watch Together.**
- **No cable or satellite logins** (TV Everywhere). Live TV comes from free services, broadcasters' own streams, tuners and playlists you add.

## Privacy

The server and the app send nothing about you to anyone: no telemetry, no analytics, no crash reporting service. When the Android or desktop app crashes, it keeps the error, the app and system versions and the device model, and at its next start sends them to your own server; the Send crash reports to your server setting turns it off. The app talks to your server and, for the update check, to GitHub's public releases API with no identifier ([app security](../app/docs/security.md)). The server's requests go to the sources, guides and logos you configure, to TMDB when you give it a token, to the iptv-org database if you switch it on, to MusicBrainz and the lyrics, artwork and artist services it feeds on for a Music library unless `SOFA_MUSICBRAINZ=false`, and to the container registry for its update check ([outbound requests](../backend/docs/security.md#outbound-requests)).

Moving from Plex or Jellyfin? [Coming from Plex or Jellyfin](migrating.md) keeps your folders and your other server running while the household switches.
