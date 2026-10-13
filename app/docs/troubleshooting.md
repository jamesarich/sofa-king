# Troubleshooting and FAQ

Start with the message on screen, then the log. The app logs under the tag `SofaKing`: on Android, `adb logcat -s SofaKing` shows it; on a desktop, `./gradlew :ui:run` prints it; on the web, the browser's console has it. The API token is never logged. Many problems start on the server, so its [troubleshooting](https://github.com/jamesarich/sofa-king-backend/blob/main/docs/troubleshooting.md) is the next place to look.

## Connecting

| Symptom | Check |
| --- | --- |
| Connect to your Sofa King server | No server is set. Focus starts on Settings; press OK and enter the Server URL and API token, then select Save & reload. |
| The Server URL field says to enter the full address | Enter the scheme, host and port, for example `http://192.168.1.10:9193`. Credentials, queries and fragments are refused. |
| Couldn't reach the Sofa King server | Check the address, the network and that the server is running: `curl --fail http://SERVER_HOST:9193/readyz` answers `ready`. Select Refresh to try again; the app also retries every minute. |
| `AUTH_REQUIRED` | The API token does not match the server's `SOFA_API_TOKEN`. Its `apiToken` is in the server's `secrets.json`. |
| Couldn't reach the Sofa King server, in a browser only | The server does not allow the page's origin. Add the origin, such as `http://192.168.1.20:8080`, to the server's `SOFA_CORS_ORIGINS`, or serve the web app from a reverse proxy that also forwards `/api/`, `/media/` and `/logos/` to the server and enter the proxy's address. |
| A web app on `https://` cannot reach the server | Browsers block requests from an HTTPS page to a plain-HTTP server. Put the server behind the same HTTPS proxy as the page. |

## Channels and guide

| Symptom | Check |
| --- | --- |
| No channels to play | The server lists no channel with a playable source. Check its providers and [source health](https://github.com/jamesarich/sofa-king-backend/blob/main/docs/source-health.md). |
| A channel is missing | The app lists a channel only when the server reports it playable and gives it a numeric display number. |
| No listing | The channel's guide lists nothing in the window. Check the guide on the server. |
| No guide for this channel | The server has no guide mapped to the channel. Map one there. |
| Guide out of date | The mapped guide's data has run out. Check that the guide still imports on the server. |
| A channel shows no logo | The server has no logo for it, or its logo failed to load. On the web, the proxy must forward `/logos/` too. |
| No program image in the info bar | The guide source gives no image for that program. On the web, images from the guide source's host load only when it sends CORS headers. |
| The guide fills in after the channels | Programs load after the channel list. The saved lineup stays on screen while it refreshes. |
| … is no longer on the server | The reloaded lineup has no channel with that number or name. Pick another channel from the guide. |
| The Guide key does nothing | Some TV launchers take the Guide key for themselves. Menu does the same thing. |
| Voice search opens a text field | The device has no speech recognizer, or the recognizer was dismissed. Type the search instead. |

## Playback

| Symptom | Check |
| --- | --- |
| Can't play this channel | The server could not open or keep a stream. Press OK to retry or pick another channel. |
| `CAPACITY_EXHAUSTED` | Every playback slot on the server holds a watch session, from this app or another client. Stop one, or raise the server's `SOFA_MAX_SESSIONS`. |
| `CHANNEL_NOT_FOUND` | The server's lineup changed. The app reloads it and follows the channel by number, then name. |
| Preview unavailable | The server refused the preview session or the stream failed. Focus another channel and come back. |
| A stream stops and comes back | Failed and ended streams reconnect after 2, 4, 8 and 15 seconds, then every 30 seconds, while the player is open. A stream the server replaced reconnects at once. OK retries immediately. |
| The picture freezes but no error shows | A stream that stalls without closing is not reconnected automatically. Press OK, or change channel and back. |
| A channel starts slowly with captions on | Caption timing buffers the stream by that much. Lower the channel's timing or the default. |

## Captions

| Symptom | Check |
| --- | --- |
| No captions on this channel | The stream carries no CEA-608 or CEA-708 captions. |
| Captions run behind the speech | Live captions are typed after the words are spoken. On Android, show the info bar with OK, then press Right to show them earlier on that channel. |
| Captions appear letter by letter | On Android, turn on Smooth live captions in Settings. |

## Desktop, iOS and web

| Symptom | Check |
| --- | --- |
| `VLC_NOT_INSTALLED` on a desktop | The desktop app plays through libVLC. Install VLC 3 (`sudo apt install vlc` on Ubuntu or Debian; a `.deb` installed with `apt install` brings it along), then restart the app. |
| This stream format isn't supported, on iOS or the web | The channel streams MPEG-TS, which AVPlayer and browsers do not play. Those platforms play HLS channels. |
| No sound when the web app opens a channel | The browser blocks sound until you interact with the page. Press any key or click, and the sound comes on. |
| No captions, in Chrome | Chrome's built-in HLS player exposes no caption tracks. Safari exposes the stream's CEA-608 captions. |
| No info bar or channel list over full-screen video, in a browser | The browser has no WebGL2, or the server does not allow the page's origin on media. Add the origin to `SOFA_CORS_ORIGINS` or serve the web app from the server's origin. Escape still returns to the guide. |
| macOS or Windows will not open the app | Snapshot packages are unsigned. Allow the app in the system's security settings. |

## FAQ

### Why does the app not find the server by itself?

The app has no server discovery. Enter the server's address in Settings.

### Can I use it away from home?

Only through a connection the server allows, such as a VPN or an HTTPS reverse proxy in front of the server. The server is built for a private network; see [SECURITY](../../SECURITY.md#cleartext-http).

### Does it record or play in the background?

No. The server has no DVR, and playback stops when the app leaves the screen. Returning opens the same channel at the live edge.

### Why will a snapshot APK not install over my build?

Android refuses an update signed with another key. A snapshot and a build from your machine can be signed with different debug keys, so uninstall one before installing the other.

### Does a TV need the Play Store?

No. Install the APK with `adb`, as [getting started](getting-started.md#android-tv-and-google-tv) describes.
