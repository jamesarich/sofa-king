# Using Sofa King

## Guide

The app opens full screen on the channel you last watched; Back shows the guide focused on that channel. On a first run, or when that channel is no longer in the lineup, it opens the guide.

The Now chip, the Guide key and the Menu key return to the current time on the focused channel. Search filters by channel name, number or current program. The Search key starts voice search through the TV's speech recognizer and focuses the first match; the Search chip, a TV without a recognizer, or a dismissed recognizer open the text field instead. All, Favorites and category chips select channel groups.

The focused channel plays in a muted preview after a 350 ms delay. After focus has rested on it for 5 seconds the preview's sound comes on, and after 10 seconds the channel opens full screen, continuing the preview's stream. Settings → Guide sets that delay (Off, 10 or 30 seconds) and turns preview sound off; with TalkBack touch exploration on, the channel never opens by itself; any key press restarts both timers and mutes the preview again. OK opens that channel full-screen at once. Back returns to the guide focused on the channel you were watching, including one you changed to in the player, with the preview muted. A filter or search that hides that channel switches back to All, and a time cursor left in the past moves to now. The channel just left does not reopen by itself until focus moves to another channel. Long-press OK on a program toggles its channel's favorite status. In the guide, Back moves from the grid to the selected filter chip, and from the chips or the header it exits the app; open search closes first.

The app shows its saved lineup while refreshing. Channels load before guide data. Automatic refresh runs every three hours; select Refresh to request a new load.

## Remote controls

| Key | Guide | Player |
| --- | --- | --- |
| D-pad Up | Move focus | Next channel |
| D-pad Down | Move focus | Previous channel |
| D-pad Left / Right | Move focus | Toggle the info bar; adjust caption timing when captions and the info bar are on |
| OK | Watch the channel | Show the info bar; on the info bar, open the channel list. Also tunes an entered number or retries a failed stream |
| Long-press OK | Toggle favorite | Return to the previously watched channel |
| Channel Up / Down | Move a page (4 rows) | Next / previous channel |
| Captions (CC) | — | Toggle captions |
| Play/Pause | — | Pause or resume; resume after 30 seconds returns to live |
| Digits | Move to the row with that number, showing All when the filter hides it; OK or a short pause commits | Enter a channel number, then press OK |
| Previous / Last | — | Return to the previously watched channel, also after picking a channel in the guide |
| Info | — | Toggle the info bar |
| Guide / Menu | Jump to Now | Open the guide at Now |
| Search | Search by voice | Open guide search by voice |
| Back | Close search; move from the grid to the selected filter chip; exit from the chips or header | Close the channel list or return to the guide |

In the channel list, Up/Down select a channel, OK tunes it and Back closes the list. Leaving the app stops playback and releases its session on the server. Returning opens a new session for the same channel and prepares the stream at the live edge while preserving paused intent.

## Captions

Enable captions with the CC key or in Settings. They show in the guide's preview as well as the player, in the selected style. While captions are on, press OK to show the info bar, then use Left/Right to adjust timing in half-second steps. Timing is saved per channel; Settings sets the default timing.

Caption timing buffers video so delayed live captions align with speech. Increasing it also increases tune-in delay. Caption style and smoothing are available in Settings.

## Server settings

Enter the Sofa King server's absolute HTTP(S) address with a host and optional port, for example `http://server.example:9193`. Embedded credentials, queries and fragments are not accepted.

The API token is the server's `SOFA_API_TOKEN`: letters, digits and punctuation with no spaces. Leave it empty when the server runs without one. The field shows only the token's last four characters until it is focused for editing.

Channels come from the server's catalog. A channel appears when the server reports it as playable and gives it a numeric display number; its first tag is the guide category.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| Connect to your Sofa King server | Focus starts on Settings; press OK and enter the server address and API token. |
| Couldn't reach the Sofa King server | Check the address, token, network access and that the server is running. `AUTH_REQUIRED` means the token does not match. Select Refresh. |
| No channels to play | The server has no channel with a playable source. Check its providers and source health. |
| No listing | The channel has no guide data on the server; check its guide assignment there. |
| Can't play this channel | The server could not open or keep a stream. Press OK to retry or select another channel. `CAPACITY_EXHAUSTED` means every session slot is a watch session. `CHANNEL_NOT_FOUND` means the server's lineup changed; the app reloads it and follows the channel by number, then name. |
| … is no longer on the server | The reloaded lineup has no channel with that number or name. Pick another channel from the guide. |
| Preview unavailable | The server answered the preview session with an error, or the stream failed; focus another channel and come back. |
| Guide fills in after channels | Guide data loads after the catalog; the saved lineup remains visible during refresh. |
| Slow tune with captions | Reduce caption timing if the added buffer is too long. |

Failed or ended streams reconnect after 2, 4, 8, 15, then every 30 seconds while the player is open and the app is started; a stream the server replaced reconnects at once. OK retries immediately. A stalled stream that remains open does not trigger an automatic reconnect.
