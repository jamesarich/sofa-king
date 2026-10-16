# Using Sofa King

This page covers the guide, the channels it lists, the remote and keyboard controls, captions and the settings. [Getting started](getting-started.md) connects the app to a server first.

## Guide

The app opens full screen on the channel you last watched; Back shows the guide focused on that channel. When you have not watched a channel yet, or that channel is no longer in the lineup, it opens the guide. Settings → Open on start can open the guide instead, or one chosen channel. With no server set, the guide shows Connect to your Sofa King server with the header's Settings button focused; press OK and [connect to the server](getting-started.md#3-connect-to-the-server).

The Now chip, the Guide key and the Menu key return to the current time on the focused channel. Fast Forward and Rewind move the grid a whole day ahead and back, up to six days, keeping the time of day and the focused channel; the range above the channel names reads Tomorrow or the weekday, and a day's programs load the first time you move to it. Search filters by channel name, number or current program, and lists a channel's later programs that match on a Later today line under its row, each with its start time; OK on one moves the grid to that program. The Search key starts voice search through the TV's speech recognizer and focuses the first match; the Search chip, a TV without a recognizer, or a dismissed recognizer open the text field instead. All, Favorites, Recent (the last ten channels watched, newest first) and category chips select channel groups, and the app reopens on the chip you chose last.

The focused channel plays in a muted preview after a 350 ms delay. After focus has rested on it for 5 seconds the preview's sound comes on, and after 10 seconds the channel opens full screen, continuing the preview's stream. Settings → Guide sets that delay (Off, 10 or 30 seconds) and turns preview sound off. The channel never opens by itself while TalkBack touch exploration on Android or VoiceOver on iOS is on, or while the preview shows Preview unavailable. Any key press restarts both timers and mutes the preview again. A touch or mouse press or scroll restarts them too, but after one the channel does not open by itself until the next key press, so scrolling the guide by hand never opens a channel under your finger. OK opens that channel full-screen at once. Back returns to the guide focused on the channel you were watching, including one you changed to in the player, with the preview muted. A filter or search that hides that channel switches back to All, and a time cursor left in the past moves to now. The channel just left does not reopen by itself until focus moves to another channel. The Info key, or holding OK on a program, opens its details: the image, title, episode, time with how long is left or until it starts, rating and description, with Watch, Add to favorites (or Remove from favorites) and Close; on a TV the details open in a panel beside the grid, elsewhere in a sheet. In the guide, Back moves from the grid to the selected filter chip, and from the chips or the header it exits the app; open search closes first.

Each channel shows its logo beside its number. The details above the grid show the focused program's episode label and title, a New badge for a first airing, its rating and its image, when the guide source has them. Times follow the device's 12- or 24-hour clock setting. A channel without programs says why: No listing when its guide source lists nothing then, No guide for this channel when the server has no guide source mapped to it, and Guide out of date when the guide source's data has run out.

The app shows its saved lineup while refreshing. Channels load before guide data. Automatic refresh runs every three hours; select Refresh to request a new load.

## Channels

Channels come from the server's lineup. The app lists a channel when the server reports it as playable and gives it a numeric display number, and lists them in number order. The server tags each channel with its lineup group, such as News, and each tag is a guide category with its own chip; a channel with several tags is under each, and one without a tag is under Other. Chips show how many channels they list.

## Remote controls

| Key | Guide | Player |
| --- | --- | --- |
| D-pad Up | Move focus | The channel above in the guide |
| D-pad Down | Move focus | The channel below in the guide |
| D-pad Left / Right | Move focus | Show the info bar; on the info bar, open the channel list. For a few seconds after the CC key, adjust the channel's caption timing |
| OK | Watch the channel | Show the info bar; on the info bar, open the channel list. Also tunes an entered number or retries a failed stream |
| Long-press OK | Open the program's details | Return to the previously watched channel |
| Channel Up / Down | Move a page (4 rows) | The next higher / lower channel number |
| Captions (CC) | | Toggle captions |
| Play/Pause | | Pause or resume; resume after 30 seconds returns to live |
| Digits | Move to the row with that number, showing All when the filter hides it; OK or a 1.5-second pause commits | Enter a channel number; OK or a 1.5-second pause tunes it |
| Previous / Last | | Return to the previously watched channel, also after picking a channel in the guide |
| Info | Open the program's details | Toggle the info bar; held, open the stream card |
| Fast Forward / Rewind | A day ahead / back | |
| Guide / Menu | Jump to Now | Open the guide at Now |
| Search | Search by voice | Open guide search by voice |
| Back | Close search; move from the grid to the selected filter chip; exit from the chips or header | Close the channel list or the stream card, or return to the guide |

On a desktop or in a browser, the arrow keys move like the D-pad, Enter is OK (hold it for a long press), Escape is Back, Page Up and Page Down move a page, the digit keys enter a channel number and C toggles captions. Touch and a mouse select directly. In the full-screen player, a tap or click shows the info bar with a row of icon buttons under it for the guide, channel down, pause or play (retry while the stream has failed), channel up, the channel list, captions and the stream card; another tap hides it, a tap with the channel list or the stream card open closes it, and a swipe up on a touch screen opens the channel list.

On a TV the screens keep 48 dp at the sides and 27 dp at the top and bottom clear for overscan. On phones, tablets, desktops and browsers they keep clear of the system bars, camera cutouts and the on-screen keyboard instead. A window narrower than 600 dp shows the guide's channel column as logo and number only, and a window narrower than 600 dp or shorter than 480 dp leaves out the preview so the grid keeps its rows.

A channel opened from Favorites or a category chip keeps channel up and down and the channel list inside that filter, and the info bar names it with the channel's place, for example "Sports 3/214". Typing a number outside it widens them to the whole lineup. Leaving the app stops playback and releases its session on the server. Returning opens a new session for the same channel and prepares the stream at the live edge while preserving paused intent.

The channel list opens on OK, Left or Right while the info bar shows. Its chips are Recent (the channels watched lately, newest first), Favorites, the guide filter the channel was opened from, and All; Up/Down select a channel, Left/Right move along the top row, OK tunes the focused channel, Back closes the list, and it closes on its own after 8 seconds without a key. Each row shows the channel's number, logo, name, the program on now with its progress, and the next program with its start time. The last channel watched is pinned above the All and filter lists. Picking a channel under All from a filtered player widens channel up and down to the whole lineup.

The end of the top row holds the current channel's buttons, so a remote with only a D-pad, OK and Back reaches everything the dedicated keys do: the star adds the channel to the favorites or removes it, Captions toggles them and closes the list so Left and Right can set the timing, Pause or Play (Retry while the stream has failed) does what the Play/Pause key does, and Stream info opens the stream card.

The info bar shows the channel's logo and the current program's title, badges, episode, time and description, with the program's image when the guide source has one. The first three bars after install end with a one-line key legend, which the stream card carries from then on.

Info held, or Stream info in the channel list, opens the stream card: the stream's audio tracks when it offers more than one (focus starts on the one playing; Up/Down and OK pick another, and the choice lasts for the channel), the video codec, picture size, frame rate and bitrate the player reports, the audio codec, the seconds buffered, the server's source for the stream and the session id. Back, Info, Left or Right closes it, OK does too when there are no tracks to pick, and it closes on its own after 15 seconds. On a desktop the card lists libVLC's tracks and media information; on iOS it shows the picture size and bitrate only; in a browser it shows the picture size.

Pause shows how many seconds the picture is behind live and that resuming after 30 seconds paused rejoins live; when it does, "Live" shows for a moment. A failed stream's card counts down to the next automatic reconnect, and a channel the server has no working source for says so.

## Captions

Enable captions with the CC key or in Settings. They show in the guide's preview as well as the player.

Where captions are drawn depends on the platform:

- On Android, the app draws them in the selected caption style.
- On a desktop, libVLC draws them into the picture, and on iOS, AVPlayer draws them in the iOS caption style. The app's caption style does not apply.
- In a browser, the app draws them in the selected style while it draws the video into its own canvas. When it falls back to the browser's video element, without WebGL2 or when the server sends no CORS headers on media, the browser draws them. Chrome's built-in HLS player shows none.

On Android, caption timing shifts captions against the picture so live captions align with speech. Press CC, then within three seconds use Right to show them earlier or Left to show them later, in half-second steps, up to 8 seconds either way; each press keeps the window open and the toast shows the timing. The info bar shows the channel's timing as "CC 2.0 s early" or "CC 1.5 s late". Timing is saved per channel; Settings sets the default timing, in the same steps and range. Showing captions earlier buffers the video by that much, so it also means a longer wait when a channel starts; showing them later does not.

## Apple TV

The Apple TV app lists the channels, favorites first, with the program on now and next; select one to watch it. Hold the touch surface on a channel for Program details, which shows the program's image, episode, rating, time left, description and what follows, with Watch and the favorite toggle, or add the channel to the favorites from the same menu. In the player, swipe down for the transport bar: its menu stars the channel and lists the recent channels, the favorites and every channel, so switching never leaves the player. The Home screen's Top Shelf shows the last ten channels watched, newest first, while the app is in the top row; selecting one opens the app on that channel.

## Settings

Settings opens from the guide's header. Switches and choices apply at once. The server fields apply when you select Save & reload, at the top of the page.

| Section | Setting | Default | What it does |
| --- | --- | --- | --- |
| Server | Server URL | Empty | The server's absolute HTTP(S) address with a host and optional port, for example `http://server.example:9193`. Embedded credentials, queries and fragments are refused |
| Server | API token | Empty | The server's API token, found as [getting started](getting-started.md#what-you-need) describes: letters, digits and punctuation with no spaces. Leave it empty when the server runs without one. The field shows only the last four characters until it is focused |
| Server | Pairing code | Empty | Six digits from the admin portal's Viewers page, entered with the Server URL instead of the token. Pair claims the code and stores the token the server hands out; a code lasts ten minutes and works once. On Android, a `sofaking://pair` link (the portal's QR code) pairs without typing |
| Startup | Open on start | Last channel | What a cold start opens: the channel watched last, the guide, or a channel chosen from the lineup with Choose. A chosen or last channel the lineup no longer has opens the guide |
| Guide | Open focused channel automatically | After 10 seconds | How long focus rests on a channel before it opens full screen: Off, after 10 or after 30 seconds. TalkBack touch exploration on Android and VoiceOver on iOS turn it off |
| Guide | Play preview sound | On | Whether the preview's sound comes on after 5 seconds |
| Guide | Time format | Auto | How the guide writes times: Auto follows the device's 12- or 24-hour setting (the locale's pattern on a desktop or the web) |
| Guide | Guide rows | Comfortable | The height of the guide's rows; Compact fits more channels on screen |
| Guide | Manage channels | | Opens the channel manager, below |
| Captions | Closed captions | Off | Shows captions in the player and the preview. The CC key toggles it too |
| Captions | Smooth live captions | On | On Android, holds roll-up text until each line is complete, so captions appear a line at a time |
| Captions | Caption style | System | Where the app draws captions, as [captions](#captions) lists. System follows Android's caption preferences, or white on black in a browser; Large, high contrast overrides them |
| Captions | Default caption timing | As broadcast | On Android, how far captions are shown earlier or later on channels without their own timing |
| Captions | Open system caption settings | | On Android, opens the system's captioning preferences; on iOS, the app's page in Settings. The line under it names the engine that draws captions on this device |
| Playback | Match frame rate | Off | Android only. Switches the display to the stream's frame rate while it plays, through the window's preferred display mode, so 29.97 fps video plays at 59.94 Hz and 25 fps at 50 Hz. Some TVs show a black screen for a moment at each switch; the launcher's rate returns when the app leaves the player |
| Playback | Decoder | Auto | Android only. Software puts the software decoders first, for the odd channel a hardware decoder cannot play; it uses more power and may drop frames at high resolutions |
| Playback | Buffer | Standard | Android only. How much of a stream downloads before it plays: Start faster waits for 0.5 s (1 s after a stall), Standard 1 s (2 s), Smoother 4 s (6 s) |
| Lock | Set a PIN | Off | Four digits, entered twice, that Settings asks for before it opens; see [settings lock](#settings-lock). Remove the PIN takes it off |

About shows the version and how many channels are loaded, then whether a newer build is published. On a snapshot, every build shows a version ending in `-dev.RUN+COMMIT`, the workflow run number and the short commit; only the desktop packages' own version, which the installer shows, is the plain one.

### Update check

Opening Settings asks GitHub once per app start for the rolling `snapshot` prerelease and compares its run number with the running build's: the line reads Up to date, Update available with the published version, or Could not reach GitHub; Check again asks once more. A build without a snapshot suffix cannot be compared, and the line shows the published version instead. With an update available, Get the update opens the Android package's download address on Android (a phone or tablet installs it through the browser; an Android TV usually has nothing to open it with, so the address is shown and the package is installed with `adb install -r`), or the release page on a desktop or in a browser. No package is published for iOS or tvOS, and the line says so. The app never downloads or installs anything by itself.

### Settings lock

With a PIN set, opening Settings shows a lock page instead: enter the PIN and select Unlock. The PIN keeps a child or a guest out of Settings, the channel manager and the startup picker; watching, zapping, the CC key, favorites in the player and pairing from a `sofaking://pair` link are not affected. The unlock lasts until you leave Settings. After five wrong PINs in a row, Unlock waits 30 seconds. The PIN is stored on this device as a salted hash, never on the server, and does not sync to other devices.

Forgot the PIN, on the lock page, takes the server's API token instead: the admin portal shows it under Settings, Reveal the API token, and a match removes the PIN. On a server that runs without a token, the PIN can only be removed by clearing the app's data on the device.

### Channel manager

Manage channels, under Guide, lists the favorites in the order the guide's Favorites filter and the player's channel list show them; the arrows beside a favorite move it. Channels not yet placed follow by number. Below, the lineup's groups (the guide's chips) each have a switch and a count of how many of their channels are shown: switched off, every channel of the group leaves the guide and the channel list on this device only, so the server and other devices keep it; switched on, they all return. The arrow beside a group opens its channels as switches of their own, so one channel can be hidden or shown by itself; a channel filed under two groups appears under both and follows the last switch that touched it. Skip hidden channels with Channel Up and Down takes them out of zapping too; a hidden channel entered by number still plays, with the full lineup around it.

[Troubleshooting](troubleshooting.md) covers connection errors, empty guides and streams that do not play.
