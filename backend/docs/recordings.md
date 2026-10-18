# Recordings

The server records programs from the guide into the [recordings folder](deploy.md#the-recordings-folder) (`SOFA_RECORDINGS_DIR`), as MPEG-TS files named the way Plex, Jellyfin, Emby and Kodi scan them. Manage recordings and series rules on the portal's [Recordings](admin.md#recordings) page, or through the [API](api.md#recordings) with the API token.

## Record a program

`POST /api/v1/recordings` with a channel id and the program's start, as the [guide query](api.md#query-the-guide) gives them, records that one program:

```sh
curl -sS -X POST -H "Authorization: Bearer $SOFA_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"channelId":"CHANNEL_UUID","startsAt":"2026-10-17T23:00:00Z"}' http://localhost:9193/api/v1/recordings
```

The recording runs from one minute before the program to two minutes after it. Asking again for the same program answers the recording already scheduled.

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

The schedule is rebuilt after every guide import, every rule change and every 30 minutes, over the days the guide covers (`SOFA_XMLTV_DAYS`, 3 by default). An airing is skipped when a recording of the same episode is already scheduled, running or kept: the same season and episode numbers, or the same episode title when the guide gives no numbers. An airing with neither, such as a news show, is recorded every time. When the guide moves or drops an airing, its scheduled recording follows. Deleting a scheduled airing of a rule cancels it for good; deleting the rule drops its scheduled recordings and keeps what it recorded.

## While a recording runs

A recording takes one playback slot (`SOFA_MAX_SESSIONS`) for its whole window and shows as `DVR` on the portal's Watching now. It opens the channel the way a player does, so the session picks the channel's healthiest source. FFmpeg stream-copies the source, without transcoding, into `.inprogress/` under the recordings folder. When the source drops or goes bad, the recording reopens the channel, on the next source when that one is no longer eligible, five seconds later, and appends to the same file. At the end the file moves to its place in the layout.

A recording that finds every slot taken retries until its window closes, and a recording can take the slot of a preview or an idle player but never of someone watching. The list marks a scheduled recording as `conflict` when it overlaps more recordings than there are slots: a running recording keeps its slot, a one-off outranks every rule, and then rule priority and the earlier-made recording win.

A server restart in the middle of a window resumes the recording into the same file. One whose whole window passed while the server was down is marked `failed` with reason `MISSED`.

## Cleanup

The DVR deletes finished recordings it no longer keeps, after every recording ends and every 30 minutes, as the `dvr-cull` job on the portal's Jobs page:

1. A rule with a `keep` count keeps its newest recordings up to that count; protected ones count toward it.
2. With `SOFA_RECORDINGS_KEEP_DAYS` set, any recording older than that many days.
3. While the recordings folder's disk has less free space than `SOFA_RECORDINGS_FREE_GIB` (10 GiB by default), the next recording in order: watched ones first, then the oldest.

A protected recording is never deleted, nor is a scheduled or running one. Each deletion removes the file and any folders it leaves empty, and logs a `recording culled` line with its reason, `KEEP`, `AGE` or `SPACE`. Both settings are runtime settings on the portal's Settings page.

## States

| `state` | Meaning |
| --- | --- |
| `scheduled` | Waiting for its window to open |
| `recording` | Running now; `path` names the file in `.inprogress/` |
| `completed` | Kept. `path` is where it is in the recordings folder; `reason` says why one was cut short (`STOPPED`, or the source's last failure) |
| `failed` | Nothing was recorded. `reason` is `MISSED`, `NO_MEDIA` or why the source could not be read, such as `SOURCE_UNAVAILABLE` or `CAPACITY_EXHAUSTED` |

`DELETE /api/v1/recordings/{id}` cancels a scheduled recording, stops a running one and keeps what it recorded, or deletes a finished one with its file and any folders it leaves empty. `PATCH` sets `protected`, and the `positionMs` and `watched` a player saves.

## Limitations

- A TS source recorded while someone watches the same channel opens a second connection to it.
- Recordings need the guide: a channel without one cannot be recorded, and there is no manual time-based recording.
- Cleanup runs after the fact: a recording that fills the disk while it runs is not stopped, and the next cleanup frees space only by deleting finished recordings.
- No player in the app opens recordings yet.
