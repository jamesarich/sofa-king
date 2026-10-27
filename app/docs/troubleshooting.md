# Troubleshooting and FAQ

Start with the message on screen, then the log. The app logs under the tag `SofaKing`: on Android, `adb logcat -s SofaKing` shows it; on a desktop, `./gradlew :ui:run` prints it; on the web, the browser's console has it. The API token is never logged. Many problems start on the server, so its [troubleshooting](../../backend/docs/troubleshooting.md) is the next place to look.

## Connecting

| Symptom | Check |
| --- | --- |
| Connect to your Sofa King server | No server is set. Focus starts on the header's Settings button; press OK, choose or enter the Server address, enter the pairing code from the admin portal and select Pair. |
| Servers on this network does not appear, or does not list the server | The app lists servers that [advertise themselves over mDNS](../../backend/docs/api.md#discovery), on Android, Android TV, desktops, iPhone, iPad and Apple TV; the web app does not look. On an iPhone or iPad, allow Local Network for Sofa King in the system's settings. The device must be on the server's subnet, and the server's log says `mdns: advertising` at the start. On Android 17, allow Nearby devices for Sofa King, which the app asks for at its first start, in the system's app settings. Otherwise enter the address. |
| The Server address field says to enter the server's address | Enter the host, with its port when it is not 9193, for example `192.168.1.10`, or the whole URL, for example `http://192.168.1.10:9193`. A path, credentials, queries and fragments are refused. |
| On Apple TV, the keyboard closes by itself while you type | tvOS's keyboard sometimes loses track of which key it is on, and an arrow press then would stop the app; the app closes the keyboard instead. What you typed stays in the field; select the field to go on typing. |
| Couldn't reach the Sofa King server | On Android 17, Sofa King needs the Nearby devices permission to reach the local network; allow it in the system's app settings. Check the address, the network and that the server is running: `curl --fail http://SERVER_HOST:9193/readyz` answers `ready`. Select Try again, which has focus, or Settings → Server → Reload to try again; the app also tries again after 5, 10, 20 and 40 seconds, then every minute. |
| `AUTH_REQUIRED` | The API token does not match the server's. A token the server generated is `apiToken` in its `secrets.json`; a token set with `SOFA_API_TOKEN` is not stored there. The admin portal's [Settings page](../../backend/docs/admin.md#settings) reveals the API token under Advanced either way. |
| Couldn't reach the Sofa King server, in a browser only | The server does not allow the page's origin. Serve the web app one of the ways [getting started](getting-started.md#web) describes. Behind a proxy, select Use this page's address under Server in Settings, then pair again. |
| A web app on `https://` cannot reach the server | Browsers block requests from an HTTPS page to a plain-HTTP server. Put the server behind the same HTTPS proxy as the page, as [getting started](getting-started.md#web) describes. |

## Channels and guide

| Symptom | Check |
| --- | --- |
| No channels to play | The server lists no channel with a playable source. Check its providers and [source health](../../backend/docs/source-health.md). |
| A channel is missing | The app lists only the [channels](usage.md#channels) the server reports as playable with a numeric display number. Check the channel on the server. |
| No listing | The channel's guide source lists nothing in the window. Check the guide source on the server. |
| No guide for this channel | The server has no guide source mapped to the channel. Map one there. |
| Guide out of date | The mapped guide source's data has run out. Check that it still imports on the server. |
| A channel shows no logo | The server has no logo for it, or its logo failed to load. On the web, the proxy must forward `/logos/` too. |
| No program image in the info bar | The guide source gives no image for that program. On the web, images from the guide source's host load only when it sends CORS headers. |
| The guide fills in after the channels | Programs load after the channel list. The saved lineup stays on screen while it refreshes. |
| … is no longer on the server | The reloaded lineup has no channel with that number or name. Pick another channel from the guide. |
| The Guide key does nothing | Some TV launchers take the Guide key for themselves. Menu does the same thing. |
| Voice search opens a text field | The device has no speech recognizer, or the recognizer was dismissed. Type the search instead. |

## Playback

A failed stream shows a headline, a line saying the player reconnects automatically, and the code the player or the server reported.

| Symptom | Check |
| --- | --- |
| Can't play this channel | The server could not open a playback session, the stream ended, or the failure has no closer headline; the code names why. Press OK to retry or pick another channel. |
| Can't reach the stream | The player could not connect to the stream or lost it. On a desktop with VLC installed, every failure the player reports shows this headline; on iOS, a connection that failed or timed out; in a browser, every one except an unsupported format. The player reconnects with backoff; press OK to retry now. |
| The stream refused the connection | On Android and iOS, the server's relay answered the stream request with an HTTP error, for example while it switches sources. The player reconnects with backoff; press OK to retry now. |
| This stream format isn't supported | On Android, the player could not read or decode the stream; on iOS, it did not recognize the format. In a browser, the channel streams MPEG-TS or another format the browser does not play; the web app plays HLS channels. |
| `CAPACITY_EXHAUSTED` | Every playback slot on the server holds a watch session, from this app or another client, or one session has more than 12 media requests open at once. Stop a session, or raise the server's `SOFA_MAX_SESSIONS`. |
| `CHANNEL_NOT_FOUND` | The server's lineup changed. The app reloads it and follows the channel by number, then name. |
| Preview unavailable | The server refused the preview session or the stream failed. The channel does not open full screen by itself. Focus another channel and come back. |
| A stream stops and comes back | Failed and ended streams reconnect after 2, 4, 8 and 15 seconds, then every 30 seconds, while the player is open. On Android and iOS, a stream the server replaced, which its relay answers with 410, reconnects at once. OK retries immediately. |
| The picture freezes but no error shows | A stream that stalls without closing is not reconnected automatically. Press OK, or change channel and back. |
| A channel starts slowly with captions on | An earlier caption timing buffers the stream by that much. Lower the channel's timing or the default; a later timing needs no buffer. |
| No working source for this channel right now | The server answered `SOURCE_UNAVAILABLE`: every source for the channel is down or quarantined. The server rechecks sources on its own; the card counts down to the next try, and OK tries at once. |
| The wrong language plays | Hold Info for the stream card and pick another audio track; the choice lasts for the channel. One track means no choice. |

## Library and recordings

| Symptom | Check |
| --- | --- |
| No Library tab | The server has no media library, or is too old to serve them. Add one on the admin portal's [Library page](../../backend/docs/admin.md#library); the tab appears within five minutes. |
| No DVR tab or Record button | The server is too old to record; update it. Record shows only on programs that have not ended. |
| A line under Record says the server did not take it | The program is not in the server's guide, or has ended. The server's [troubleshooting](../../backend/docs/troubleshooting.md#recordings) has the rest. |
| `CAPACITY_EXHAUSTED` playing a library item or a recording | The server is already converting as many files as `SOFA_MAX_TRANSCODES` allows, and none came free in the 20 seconds the player waited. Stop playback elsewhere and try again. |
| A jump in a library item takes a moment | The server converts that file as it plays, and a jump past what it has converted restarts the conversion there. |
| No commercial breaks on the seek bar | The server's Comskip scan found none, or has not finished; Skip commercials has nothing to skip. |

## Captions

| Symptom | Check |
| --- | --- |
| No captions on this channel | The stream carries no CEA-608 or CEA-708 captions. |
| Captions run behind the speech | Live captions are typed after the words are spoken. On Android, press CC, then Right within a few seconds to show them earlier on that channel. |
| Captions run ahead of the speech | Some relays carry captions early. On Android, press CC, then Left within a few seconds to show them later on that channel, up to 8 seconds. |
| Captions appear letter by letter | On Android, turn on Settings → Captions → Smooth live captions. |
| The caption style setting changes nothing | On a desktop and on iOS the player draws captions itself, and in a browser so does the browser's video element when the app falls back to it. [Captions](usage.md#captions) lists where the style applies. |

## Desktop, iOS and web

| Symptom | Check |
| --- | --- |
| `VLC_NOT_INSTALLED` on a desktop | The desktop app plays through libVLC. Install VLC 3 (`sudo apt install vlc` on Ubuntu or Debian; a `.deb` installed with `apt install` brings it along), then restart the app. |
| An MPEG-TS channel does not play on iOS | AVPlayer plays HLS channels only. Watch MPEG-TS channels on Android or a desktop. |
| The screen dims or locks while a channel plays, on a Linux desktop or in a browser | A Linux desktop follows its own idle settings, since the app keeps the display awake only on Windows and macOS. A browser keeps it awake only on a page served over HTTPS, and only in browsers with the Screen Wake Lock API. |
| No sound when the web app opens a channel | The browser blocks sound until you interact with the page. Press any key or click, and the sound comes on. |
| No captions, in Chrome | Chrome's built-in HLS player exposes no caption tracks. Safari exposes the stream's CEA-608 captions. |
| No info bar or channel list over full-screen video, in a browser | The browser has no WebGL2, or the server sends no CORS headers on media for the page's origin. Serve the web app one of the ways [getting started](getting-started.md#web) describes. Escape still returns to the guide. |
| macOS or Windows will not open the app | Snapshot packages are unsigned. Allow the app in the system's security settings. |

## FAQ

### Why does the app not find the server by itself?

On Android, Android TV, desktops, iPhone, iPad and Apple TV, Settings lists the servers on the same network under Servers on this network; when yours is missing, see the first row of the table above. The web app does not look, so enter the server's address in Settings.

### Can I use it away from home?

Only through a connection the server allows, such as a VPN or an HTTPS reverse proxy in front of the server. The server is built for a private network; see [cleartext HTTP](security.md#cleartext-http).

### Does it record or play in the background?

It records: on a server that records, the program details offer Record and Record series, and recordings play from the DVR tab, as [recordings](usage.md#recordings) describes. On an Android phone or tablet, leaving the app while something plays shrinks it into a [picture-in-picture](usage.md#remote-controls) window that keeps playing. Elsewhere it does not play in the background: playback stops when the app leaves the screen, and returning opens the same channel at the live edge, or a library item or recording where it stopped.

### Why will a snapshot APK not install over my build?

Android refuses an update signed with another key. A snapshot and a build from your machine can be signed with different keys, so uninstall one before installing the other. The same goes once for every device that installed a snapshot signed with the debug key, when snapshots move to the release key ([signing](development.md#signing)): uninstall the app, then install the new snapshot. Install update in Settings checks the key first and says so, rather than handing Android a package it would refuse.

### Does a TV need the Play Store?

No, Sofa King is not in a store. Install the APK with the Downloader app or with `adb`, as [getting started](getting-started.md#android-tv-and-google-tv) describes; after that, Settings → About installs newer snapshots, as [the update check](usage.md#update-check) describes.
