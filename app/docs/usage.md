# Using Sofa King

This page covers the guide, the remote and keyboard controls, captions and the settings. [Getting started](getting-started.md) connects the app to a server first.

## Guide

The app opens full screen on the channel you last watched; Back shows the guide focused on that channel. On a first run, or when that channel is no longer in the lineup, it opens the guide.

The Now chip, the Guide key and the Menu key return to the current time on the focused channel. Search filters by channel name, number or current program. The Search key starts voice search through the TV's speech recognizer and focuses the first match; the Search chip, a TV without a recognizer, or a dismissed recognizer open the text field instead. All, Favorites and category chips select channel groups.

The focused channel plays in a muted preview after a 350 ms delay. After focus has rested on it for 5 seconds the preview's sound comes on, and after 10 seconds the channel opens full screen, continuing the preview's stream. Settings → Guide sets that delay (Off, 10 or 30 seconds) and turns preview sound off; with TalkBack touch exploration on, the channel never opens by itself; any key press restarts both timers and mutes the preview again. OK opens that channel full-screen at once. Back returns to the guide focused on the channel you were watching, including one you changed to in the player, with the preview muted. A filter or search that hides that channel switches back to All, and a time cursor left in the past moves to now. The channel just left does not reopen by itself until focus moves to another channel. Long-press OK on a program toggles its channel's favorite status. In the guide, Back moves from the grid to the selected filter chip, and from the chips or the header it exits the app; open search closes first.

Each channel shows its logo beside its number. The details above the grid show the focused program's episode label and title, a New badge for a first airing, and its rating, when the guide has them. A channel without programs says why: No listing when its guide lists nothing then, No guide for this channel when the server has none mapped, and Guide out of date when the guide's data has run out.

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
| Captions (CC) | | Toggle captions |
| Play/Pause | | Pause or resume; resume after 30 seconds returns to live |
| Digits | Move to the row with that number, showing All when the filter hides it; OK or a short pause commits | Enter a channel number, then press OK |
| Previous / Last | | Return to the previously watched channel, also after picking a channel in the guide |
| Info | | Toggle the info bar |
| Guide / Menu | Jump to Now | Open the guide at Now |
| Search | Search by voice | Open guide search by voice |
| Back | Close search; move from the grid to the selected filter chip; exit from the chips or header | Close the channel list or return to the guide |

On a desktop or in a browser, the arrow keys move like the D-pad, Enter is OK (hold it for a long press), Escape is Back, Page Up and Page Down move a page, the digit keys enter a channel number and C toggles captions. Touch and a mouse select directly.

A channel opened from Favorites or a category chip keeps channel up and down and the channel list inside that filter, and the info bar names it with the channel's place, for example "Sports 3/214". Typing a number outside it widens them to the whole lineup. In the channel list, Up/Down select a channel, OK tunes it and Back closes the list. Leaving the app stops playback and releases its session on the server. Returning opens a new session for the same channel and prepares the stream at the live edge while preserving paused intent.

The info bar shows the channel's logo and the current program's title, badges, episode, time and description, with the program's image when the guide has one.

## Captions

Enable captions with the CC key or in Settings. They show in the guide's preview as well as the player, in the selected style.

On Android, caption timing buffers video so delayed live captions align with speech. While captions are on, press OK to show the info bar, then use Left/Right to adjust timing in half-second steps. Timing is saved per channel; Settings sets the default timing. More timing also means a longer wait when a channel starts.

## Settings

Settings opens from the guide's header, and on a first run the app starts there. Switches and choices apply at once. The server fields apply when you select Save & reload, at the top of the page.

| Section | Setting | Default | What it does |
| --- | --- | --- | --- |
| Server | Server URL | Empty | The server's absolute HTTP(S) address with a host and optional port, for example `http://server.example:9193`. Embedded credentials, queries and fragments are refused |
| Server | API token | Empty | The server's `SOFA_API_TOKEN`: letters, digits and punctuation with no spaces. Leave it empty when the server runs without one. The field shows only the last four characters until it is focused |
| Captions | Closed captions | Off | Shows captions in the player and the preview. The CC key toggles it too |
| Captions | Smooth live captions | On | On Android, holds roll-up text until each line is complete, so captions appear a line at a time |
| Captions | Caption style | System | System uses the platform's caption style; Large, high contrast overrides it |
| Captions | Default caption timing | 0 s | On Android, how far captions are shown earlier on channels without their own timing |
| Guide | Open focused channel automatically | After 10 seconds | How long focus rests on a channel before it opens full screen: Off, after 10 or after 30 seconds. TalkBack touch exploration turns it off |
| Guide | Play preview sound | On | Whether the preview's sound comes on after 5 seconds |

About shows the version and how many channels are loaded. A snapshot's version ends in `-dev.RUN+COMMIT`.

Channels come from the server's lineup. A channel appears when the server reports it as playable and gives it a numeric display number. The server tags each channel with its lineup group, such as News, and each tag is a guide category with its own chip; chips show how many channels they list.

[Troubleshooting](troubleshooting.md) covers connection errors, empty guides and streams that do not play.
