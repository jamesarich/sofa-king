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

In the channel list, Up/Down select a channel, OK tunes it and Back closes the list. Leaving the app stops playback and releases its session on the server. Returning opens a new session for the same channel and prepares the stream at the live edge while preserving paused intent.

## Captions

Enable captions with the CC key or in Settings. While captions are on, press OK to show the info bar, then use Left/Right to adjust timing in half-second steps. Timing is saved per channel; Settings sets the default timing.

Caption timing buffers video so delayed live captions align with speech. Increasing it also increases tune-in delay. Caption style and smoothing are available in Settings.

## Server settings

Enter the Sofa King server's absolute HTTP(S) address with a host and optional port, for example `http://192.168.1.3:9193`. Embedded credentials, queries and fragments are not accepted.

The API token is the server's `SOFA_API_TOKEN`: letters, digits and punctuation with no spaces. Leave it empty when the server runs without one. The token is shown as typed; the TV keyboard has no masked entry.

Channels come from the server's catalog. A channel appears when the server reports it as playable and gives it a numeric display number; its first tag is the guide category.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| Connect to your Sofa King server | Focus starts on Settings; press OK and enter the server address and API token. |
| Couldn't reach the Sofa King server | Check the address, token, network access and that the server is running. `AUTH_REQUIRED` means the token does not match. Select Refresh. |
| No channels to play | The server has no channel with a playable source. Check its providers and source health. |
| No listing | The channel has no guide data on the server; check its guide assignment there. |
| Can't play this channel | The server could not open or keep a stream. Press OK to retry or select another channel. `CAPACITY_EXHAUSTED` means every session slot is a watch session. |
| Preview unavailable | The server answered the preview session with an error, or the stream failed; focus another channel and come back. |
| Guide fills in after channels | Guide data loads after the catalog; the saved lineup remains visible during refresh. |
| Slow tune with captions | Reduce caption timing if the added buffer is too long. |

Failed or ended streams reconnect after 2, 4, 8, 15, then every 30 seconds while the player is open and the app is started; a stream the server replaced reconnects at once. OK retries immediately. A stalled stream that remains open does not trigger an automatic reconnect.
