# Coming from Plex or Jellyfin

Sofa King reads your folders as they are and can feed your current server while the household moves over, so nothing has to switch on one evening.

## Keep your folders

The library scan reads the names Plex, Jellyfin and Kodi read: `Title (Year)` movie folders, show folders with `Season 01`, `S01E02` episode names, `.nfo` files, local artwork and the `{tmdb-}`, `{tvdb-}` and `{imdb-}` tags Sonarr and Radarr write ([media libraries](../backend/docs/libraries.md)). Mount the same folders read-only into the Sofa King container, as [deploying with Docker](../backend/docs/deploy.md#the-media-folder) describes, and add a library for each. Both servers can read them at once.

Watch history does not carry over: each item starts unwatched.

## Run both while the household switches

Sofa King's live TV can stay inside the server people already use while they try the Sofa King app:

- **Plex**: add Sofa King as an HDHomeRun tuner with its XMLTV guide, and Plex Live TV & DVR shows the whole merged lineup ([Plex Live TV](../backend/docs/live-tv.md#plex-live-tv)).
- **Jellyfin or Emby**: add Sofa King as an HDHomeRun or M3U tuner with the XMLTV guide ([Jellyfin](../backend/docs/live-tv.md#jellyfin)).
- **Channels DVR**: add the playlist as a custom channels source ([Channels DVR](../backend/docs/live-tv.md#channels-dvr)).

Then install the app on one TV ([getting started](../app/docs/getting-started.md)), pair it from the admin portal, and move the other devices when the household is ready.

## What changes

- There are no accounts. Devices pair with a code from the admin portal, and forgetting a device in the portal signs it out.
- Favorites, hidden channels, Continue watching and the watchlist belong to the household, not to a person.
- Away from home, use your own VPN; see [remote access](../backend/docs/deploy.md#remote-access).
- [How Sofa King compares](comparison.md) lists what it does not do, such as music and offline downloads.
