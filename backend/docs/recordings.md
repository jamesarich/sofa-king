# Recordings

The server records programs from the guide into the [recordings folder](deploy.md#the-recordings-folder) (`SOFA_RECORDINGS_DIR`), as MPEG-TS files named the way Plex, Jellyfin, Emby and Kodi scan them. Manage recordings and series rules on the portal's [Recordings](admin.md#recordings) page, in the app (Record in a program's details and the [Recordings tab](../../app/docs/usage.md#recordings)), or through the [API](api.md#recordings) with the API token.

## Record a program

`POST /api/v1/recordings` with a channel id and the program's start, as the [guide query](api.md#query-the-guide) gives them, records that one program:

```sh
curl -sS -X POST -H "Authorization: Bearer $SOFA_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"channelId":"CHANNEL_UUID","startsAt":"2026-10-17T23:00:00Z"}' http://localhost:9193/api/v1/recordings
```

The recording runs from one minute before the program to two minutes after it. Asking again for the same program answers the recording already scheduled.

## Record a time window

Add `endsAt`, and optionally a `title`, to record the channel for exactly that window, whether or not it has a guide:

```sh
curl -sS -X POST -H "Authorization: Bearer $SOFA_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"channelId":"CHANNEL_UUID","startsAt":"2026-10-17T23:00:00Z","endsAt":"2026-10-18T01:30:00Z","title":"Late Game"}' \
  http://localhost:9193/api/v1/recordings
```

The window runs without padding and spans at most 12 hours; without a `title` the recording takes the channel's name. It is named like a show with no episode numbers, under the date it starts. The portal's Recordings page has the same form under Record a time window.

## Record a series

A rule records every airing whose title matches, ignoring case, on one channel or on any channel:

```sh
curl -sS -X POST -H "Authorization: Bearer $SOFA_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"NOVA","newOnly":true}' http://localhost:9193/api/v1/recording-rules
```

| Field | Default | Meaning |
| --- | --- | --- |
| `title` | Required | The program title to match, 1 to 200 characters |
| `channelId` | Any channel | Limit the rule to one channel |
| `newOnly` | `true` | Skip airings the guide marks `previously-shown`, unless it also marks them `new`. A guide that marks neither makes every airing new |
| `keep` | `0`, all | How many finished recordings the rule keeps, newest first |
| `startPaddingSeconds`, `endPaddingSeconds` | `60`, `120` | How far before and after the program the recording runs, 0 to 3600 |
| `priority` | `0` | -100 to 100; the higher wins a playback slot when recordings conflict |
| `match` | By title | `{"kind", "terms", "categories"}`: what the rule matches, below. It never changes once the rule exists |

A rule's `match.kind` is `title`, `team` or `keyword`:

- `title` (or no `match`) matches the title, as above.
- `team` matches any of `terms` as a whole word, ignoring case, in the program's title or episode title, on a program with one of `categories` among its guide categories as a word (`Sports` matches `Sports event`). Without `terms` the team is the rule's `title`, and without `categories` it is `Sports`. `{"title":"Packers","match":{"kind":"team"}}` records `NFL Football` with the episode title `Bears at Packers` on any channel.
- `keyword` matches any of `terms` as a whole word in the title, episode title or description, in one of `categories` when any is given. It needs at least one term.

Each takes up to 20 terms and 20 categories of up to 100 characters. The rule's `title` then only names it in the lists; recordings take their program's title as usual. The rule list answers its `match`; a title rule answers none. The app's program details offer Record for each team a sports program names, and the portal's Recordings page adds any of the three.

`PATCH /api/v1/recording-rules/{id}` changes any of these but the title and the match; `"anyChannel":true` clears the channel. The schedule is rebuilt after every guide import, every rule change and every 30 minutes, over the days the guide covers (`SOFA_XMLTV_DAYS`, 3 by default). An airing is skipped when a recording of the same episode is already scheduled, running or kept: the same season and episode numbers, or the same episode title when the guide gives no numbers. An airing with neither, such as a news show, is recorded every time. When the guide moves or drops an airing, its scheduled recording follows. Deleting a scheduled airing of a rule cancels it for good; deleting the rule drops its scheduled recordings and keeps what it recorded.

## While a recording runs

A recording takes one playback slot (`SOFA_MAX_SESSIONS`) for its whole window and shows as `DVR` on the portal's Watching now. It opens the channel the way a player does, so the session picks the channel's healthiest source. An MPEG-TS source is read through the same upstream connection players of it share, so recording a channel someone watches opens no second connection; an HLS source is stream-copied by FFmpeg. Either way nothing is transcoded, and the file is written into `.inprogress/` under the recordings folder. When the source drops or goes bad, the recording reopens the channel, on the next source when that one is no longer eligible, five seconds later, and appends to the same file. At the end the file moves to its place in the layout.

A recording that finds every slot taken retries until its window closes, and a recording can take the slot of a preview or an idle player but never of someone watching. The list marks a scheduled recording as `conflict` when it overlaps more recordings than there are slots: a running recording keeps its slot, a one-off outranks every rule, and then rule priority and the earlier-made recording win.

A server restart in the middle of a window resumes the recording into the same file. One whose whole window passed while the server was down is marked `failed` with reason `MISSED`.

## Play a recording

A running or finished recording plays through the same [library playback sessions](api.md#play-library-files) as a library file, so the player chooses direct play, a copy into HLS or a conversion by what it can play. A recording still being made always plays as HLS that grows with the file, from its start or wherever the player seeks; it ends a minute after the file stops growing, so when the recording finishes the player reaches the end like any other file. The apps offer Watch from start on a program being recorded, from the live player and the program's details, and Back to live from the recording.

## Commercial breaks

After a recording finishes, the server runs [Comskip](https://github.com/erikkaashoek/Comskip) on it, one recording at a time, to find its commercial breaks. Comskip is a separate GPL program the image installs from Ubuntu; the server only runs it. The breaks go on the recording as `breaks`, and as a Kodi EDL beside the file (`Show - S01E02 - Title.edl`, action 3, a commercial break), which Kodi and other EDL-aware players skip. `commercials` says how far it got: `scanning`, `done` (with no `breaks` when Comskip found none) or `failed`, which the log explains. A scan a restart interrupts runs again. Set `SOFA_COMSKIP` to the executable outside the image, or to `none` to turn the scan off. Deleting a recording deletes its EDL too.

Comskip finds breaks by black frames, silence, the station logo, scene changes and aspect ratio. It misses some and marks some program as a break, more so on channels without a logo or with short or no black frames between ads; the file itself is never cut.

## Cleanup

The DVR deletes finished recordings it no longer keeps, after every recording ends and every 30 minutes, as the Recording cleanup job on the portal's Jobs page:

1. A rule with a `keep` count keeps its newest recordings up to that count; protected ones count toward it.
2. With `SOFA_RECORDINGS_KEEP_DAYS` set, any recording older than that many days.
3. While the recordings folder's disk has less free space than `SOFA_RECORDINGS_FREE_GIB` (10 GiB by default), the next recording in order: watched ones first, then the oldest.

A protected recording is never deleted, nor is a scheduled or running one. Each deletion removes the file and any folders it leaves empty, and logs a `recording culled` line with its reason, `KEEP`, `AGE` or `SPACE`. Both settings are runtime settings on the portal's Settings page.

While a recording runs, the DVR checks the free space every five seconds. Below `SOFA_RECORDINGS_FREE_GIB` it runs the cleanup at once, at most once a minute, so finished recordings make room. Below 512 MiB with nothing left to delete, every running recording stops and keeps what it wrote, with the reason `DISK_FULL`, so the disk never fills.

## States

| `state` | Meaning |
| --- | --- |
| `scheduled` | Waiting for its window to open |
| `recording` | Running now; `path` names the file in `.inprogress/` |
| `completed` | Kept. `path` is where it is in the recordings folder; `reason` says why one was cut short (`STOPPED`, `DISK_FULL`, or the source's last failure) |
| `failed` | Nothing was recorded. `reason` is `MISSED`, `NO_MEDIA`, `DISK_FULL` or why the source could not be read, such as `SOURCE_UNAVAILABLE` or `CAPACITY_EXHAUSTED` |

`DELETE /api/v1/recordings/{id}` cancels a scheduled recording, stops a running one and keeps what it recorded, or deletes a finished one with its file and any folders it leaves empty. `PATCH` sets `protected`, and the `positionMs` and `watched` a player saves.

## Subscribe in a calendar

`/output/recordings.ics` lists the scheduled and running recordings as calendar events, so a calendar app shows when the DVR records. Make a read token on the portal's Settings page, under Access tokens, and subscribe to `http://SERVER_HOST:9193/output/recordings.ics?token=TOKEN` (Google Calendar: Other calendars, From URL; Apple Calendar: File, New Calendar Subscription). The output token works there too. Each event covers the recording's window, paddings included, and a recording in a conflict carries `X-SOFA-KING-CONFLICT:TRUE`. The feed asks to be refreshed every 15 minutes; how often an app does is up to it.

## Limitations

- An HLS source recorded while someone watches the same channel opens a second connection to it; only MPEG-TS sources share one.
- A series rule needs the guide; a channel without one is recorded only as a [time window](#record-a-time-window).
- Cleanup frees space only by deleting finished recordings; when there are none left, a running recording stops rather than fill the disk.
- Breaks are skipped only by the app, with Skip commercials on, and by players that read the EDL.
