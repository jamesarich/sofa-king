# How Sofa King compares

Sofa King is one server and one app for a home network: live TV merged from free services, broadcasters' own streams, tuners and playlists, a DVR, and your own movie and TV libraries, watched on Android TV, Google TV, phones, desktops, iOS, Apple TV and the web. Everything is free and open source under GPL-3.0, and nothing needs an account.

This page says what it does next to the servers people usually come from, and what it does not do. Prices are the vendors' own, with the page and the date they were read; check the vendor before you decide.

## What is included

Every feature is in the one free build. There is no paid tier, so nothing below is locked:

- **Live TV from many sources in one lineup.** Free streaming services, broadcasters' streams, HDHomeRun tuners and any M3U playlist with its XMLTV guide merge into one numbered lineup. A channel carried by several sources is matched by schedule, and when one source fails the next takes over, as [source health](../backend/docs/source-health.md) describes.
- **A DVR** with series rules, commercial detection on every recording, and learned breaks on channels whose streams carry no markers ([recordings](../backend/docs/recordings.md)).
- **Media libraries** read with the names Plex, Jellyfin and Kodi use, matched on TMDB, with intro and credits detection, scrub thumbnails and conversion on the fly, in software or on the GPU ([media libraries](../backend/docs/libraries.md)).
- **First-party apps** on every platform above, one codebase, with the same guide, player and library on each ([using the app](../app/docs/usage.md)).
- **Household features**: shared favorites and hidden channels, Continue watching, a watchlist, On Later, and kids mode behind the Settings PIN.
- **Channels of your own**: [virtual channels](../backend/docs/virtual-channels.md) that play a library on a schedule, with live slots and breaks you choose, and [multiview](../app/docs/usage.md#multiview) of two to four channels, drawn on the server.
- **Homelab hooks**: Sonarr and Radarr tell the server what changed ([integrations](../backend/docs/integrations.md)), alerts go to a webhook, ntfy or Apprise ([notifications](../backend/docs/notifications.md)), and each device or tool gets its own token, scoped to what it needs.
- **Outputs for other players**: an HDHomeRun tuner for Plex Live TV, and an M3U playlist with an XMLTV guide for Jellyfin, Channels DVR and IPTV players ([third-party players](../backend/docs/live-tv.md#third-party-players)).

## Next to the usual servers

| | Sofa King | Plex | Jellyfin | Emby | Channels DVR |
| --- | --- | --- | --- | --- | --- |
| Price | Free | Free tier; Plex Pass lifetime $749.99 since July 1, 2026 ([Plex, May 18, 2026](https://forums.plex.tv/t/new-lifetime-plex-pass-pricing/938910)) | Free | Free tier; Emby Premiere ([options](https://emby.media/support/articles/Premiere-Membership-Options.html)) | $8 a month or $80 a year ([Channels, read October 25, 2026](https://getchannels.com/docs/getting-started/quick-start-guide/subscription/)) |
| Account | None | A Plex account | None | Emby Connect optional | A Channels account |
| Source code | Open, GPL-3.0 | Closed | Open, GPL-2.0 | Closed | Closed |
| Merges sources into one lineup, with failover | Yes | No | No | No | No |
| Own apps for TV, phone, desktop and web | Yes, one codebase | Yes | Clients from several projects | Yes | Yes |
| Library | Movies, TV, music | Movies, TV, music, photos | Movies, TV, music, books, photos | Movies, TV, music, books, photos | Imported videos |

## What Sofa King does not do

Say no early rather than after an install:

- **No relay for watching away from home.** The server has no cloud service and opens no port. Reach it from outside through your own VPN, as [remote access](../backend/docs/deploy.md#remote-access) describes.
- **No photos or books.** Libraries hold movies, TV shows, other videos and music.
- **No offline downloads** to a phone or tablet.
- **No user accounts or profiles.** The household shares one set of favorites and watch progress; kids mode limits one device.
- **No Watch Together.**
- **No cable or satellite logins** (TV Everywhere). Live TV comes from free services, broadcasters' own streams, tuners and playlists you add.

## Privacy

The server and the app send nothing about you to anyone. There is no telemetry, no analytics and no crash reporting service. When the Android or desktop app crashes, it keeps the error, the app and system versions and the device model, and at its next start sends them to your own server, never to anyone else; the Send crash reports to your server setting turns it off. The app talks to your server and, for the update check, to GitHub's public releases API with no identifier ([app security](../app/docs/security.md)). The server's requests go to the sources, guides and logos you configure, to TMDB when you give it a token, and to the container registry for its update check ([outbound requests](../backend/docs/security.md#outbound-requests)).

Moving from Plex or Jellyfin? [Coming from Plex or Jellyfin](migrating.md) keeps your folders and your other server running while the household switches.
