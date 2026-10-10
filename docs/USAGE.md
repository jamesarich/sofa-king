# Using Sofa King

## Guide

The Now chip returns to the current time. Search filters by channel name, number or current program. All, Favorites and category chips select channel groups.

The focused channel plays in a muted preview after a 350 ms delay. OK opens that channel full-screen. Back returns to the same guide position. Long-press OK on a program toggles its channel's favorite status.

The app shows its saved lineup while refreshing. Channels load before guide data. Automatic refresh runs every three hours; select Refresh to request a new load.

## Remote controls

| Key | Guide | Player |
| --- | --- | --- |
| D-pad Up | Move focus | Next channel |
| D-pad Down | Move focus | Previous channel; open the channel list when the info bar is visible |
| D-pad Left / Right | Move focus | Toggle the info bar; adjust caption timing when captions and the info bar are on |
| OK | Watch the channel | Toggle the info bar, tune an entered number, or retry a failed stream |
| Long-press OK | Toggle favorite | Return to the previously watched channel |
| Channel Up / Down | Move focus | Next / previous channel |
| Captions (CC) | — | Toggle captions |
| Play/Pause | — | Pause or resume; resume after 30 seconds returns to live |
| Digits | — | Enter a channel number, then press OK |
| Previous / Last | — | Return to the previously watched channel |
| Info | — | Toggle the info bar |
| Back | Exit | Close the channel list or return to the guide |

In the channel list, Up/Down select a channel, OK tunes it and Back closes the list. Leaving the app stops playback. Returning prepares the stream at the live edge while preserving paused intent.

## Captions

Enable captions with the CC key or in Settings. While captions are on, press OK to show the info bar, then use Left/Right to adjust timing in half-second steps. Timing is saved per channel; Settings sets the default timing.

Caption timing buffers video so delayed live captions align with speech. Increasing it also increases tune-in delay. Caption style and smoothing are available in Settings.

## Server settings

Enter an absolute HTTP(S) address with a host and optional port. Embedded credentials, queries and fragments are not accepted. The profile must be a single name, without slashes, backslashes or control characters.

The playlist must contain numeric `tvg-chno` values. M3U `tvg-id` values match XMLTV `channel` attributes; without `tvg-id`, the app uses the channel number as the guide key. Stream URLs are read from the playlist.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| Couldn't reach Dispatcharr | Check the server address, network access and server availability. Select Refresh. |
| No channels in this profile | Check the profile name and add channels to it in Dispatcharr. |
| No listing | Assign guide data to the channel; check M3U/XMLTV IDs match. |
| Can't play this channel | Test the stream in Dispatcharr. Press OK to retry or select another channel. |
| Guide fills in after channels | Guide data loads after the playlist; the saved lineup remains visible during refresh. |
| Slow tune with captions | Reduce caption timing if the added buffer is too long. |

Failed or ended streams reconnect after 2, 4, 8, 15, then every 30 seconds while the player is open and the app is started. OK retries immediately. A stalled stream that remains open does not trigger an automatic reconnect.
