# Virtual channels

A virtual channel is a channel the server plays from your [media libraries](libraries.md) on a schedule: a cartoon channel of every show in a library, a movie channel shuffled from a genre, or a daily grid with sitcoms at six and movies at eight. It reaches the lineup like any other source, so the app's guide, the [player outputs](live-tv.md), Plex and the [DVR](recordings.md) all see it as one more live channel.

## Set up

1. On the portal's **Sources** page, tick **Virtual channels** and save. The server restarts with a `virtual` provider in `sources.json`:

   ```json
   { "id": "virtual", "type": "native", "service": "virtual" }
   ```

2. With a [lineup policy](lineup-policy.md), add the provider to its `providers` on the same page, as you would any service. Without one, add the channels to the lineup from **Channels** like any other candidate.
3. Open **Channels › Virtual** and add a channel.

## Schedules

A channel has one or more **slots**. Each slot plays one source:

| Source | Plays |
| --- | --- |
| Library | Every movie, episode and video of the library, titles in order and each show's episodes in its own order |
| Show | The episodes of one show, in order |
| Genre | Every movie and show of any library whose genres include it, ignoring case |
| Live channel | A channel of the lineup as it plays now, for the slot's whole time; timed slots only |

A slot plays its items in order, continuing after the one it aired last, or shuffled, playing each item once before any again. It always airs whole items.

The slots either all have a start time or none do:

- **Untimed** slots take turns, one item each. One untimed slot plays its source end to end.
- **Timed** slots make a daily grid in the server's time zone (`TZ`). Each runs from its start until the next slot's start, airing as many whole items as fit. The first item of a slot always airs, even when it runs past the next start, which then starts late.

A live channel slot makes a channel that is "the news at six, cartoons otherwise". It airs the lineup channel from its start, or from when the item before it ends, until the next slot's start, and the guide lists that channel's own programs for the time. The lineup's virtual channels are not offered, so a channel never plays itself.

**Fill to the next mark** pads after each item to the next quarter hour, half hour or hour, so items start on the clock as broadcast TV does. The **filler** source fills those gaps, and the gap before a timed slot starts, with items that fit, such as a library of bumpers and trailers. Whatever filler cannot cover shows a black picture with silence.

## Breaks

**Breaks** are the ads you choose: a bumper, a trailer or a "time to brush your teeth" clip, and slides such as a chore chart. A break airs after an item, after every one or once 15, 30 or 60 minutes have passed since the last, and holds:

- one item of the break source, in a shuffled turn, such as a library of bumpers;
- then each slide, an image URL shown for 3 to 60 seconds with silence. The server fetches it as the slide airs, with no credentials, so the image is current. For Home Assistant, have an automation save a snapshot (`camera.snapshot`) or a rendered card into its `www` folder, which it serves at `http://homeassistant.local:8123/local/...` without a token; its entities' own image URLs carry tokens that expire within minutes. PNG, JPEG, GIF and WebP images up to 10 MB work, at most five slides a break. One that cannot be fetched shows black.

A break goes before the padding and its filler, and in a timed slot only when it fits before the next start: without room for the item, only the slides air, and without room for them, nothing does. The guide gives a break's time to the item before it, like filler.

The server builds each schedule 48 hours ahead and stores it, so the guide, the stream and a restart agree on what airs when. It extends the schedule when less than a day is left. Saving a channel rebuilds its schedule from the end of what airs now, so the current item plays on. The portal shows what is coming up under the form.

The guide lists each item as a program: an episode under its show's title, with its own as the episode title and its season and episode numbers. Filler and black time go to the program before them.

## Playback

A channel plays nothing until someone tunes in. The first request starts one FFmpeg per airing, which converts it to one format for every item, 1920×1080 at 30 frames a second with H.264 video and stereo AAC audio, so players never see the format change. It reads the file at the speed it plays, starting 12 seconds behind the schedule so a player has segments at once. Every viewer of a channel shares its one FFmpeg and sees the same picture. A stream nobody has read for a minute stops.

The conversion uses the encoder `SOFA_HWACCEL` picks, as library playback does. Each playing channel counts toward `SOFA_MAX_TRANSCODES` with the library files being converted; past it, tuning in answers `503 CAPACITY_EXHAUSTED`, and the session moves to the channel's next source if it has one.

The relay fetches the stream from the server's own loopback route, `http://127.0.0.1:{port}/local/{key}/{channel}/live.m3u8`. The listener binds 127.0.0.1 only, and the random key changes at every start, so the route is reachable from nothing but the server. The address policy admits that route alone among loopback URLs.

A live channel slot opens a playback session of its own, the way a recording does: it holds one of `SOFA_MAX_SESSIONS`, picks the channel's healthiest source, and opens the channel again when that source drops. A slot that cannot open, because every session is taken or no source plays, shows black and tries again every 30 seconds.

An item whose file is gone, or one FFmpeg cannot read, shows black for the rest of its time.

## API

The [admin API](api.md#virtual-channels) lists, creates, changes and deletes virtual channels and reads a channel's schedule ahead.
