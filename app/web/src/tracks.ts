// A library item's audio and subtitle tracks, as the player's picker lists them and its `<video>` shows them.

/** A track as core lists it; a `picture` subtitle is one the server draws into the picture. */
export interface Track {
    readonly index: number;
    readonly label: string;
    readonly picture: boolean;
}

export interface Tracks {
    readonly audio: readonly Track[];
    readonly subtitles: readonly Track[];
}

/** One line of the picker; `index` is null for Off. */
export interface TrackRow {
    index: number | null;
    label: string;
    on: boolean;
}

/** True when there is something to pick: a second audio track or any subtitles, as in the apps. */
export function offersTracks(tracks: Tracks | null): boolean {
    return tracks !== null && (tracks.audio.length > 1 || tracks.subtitles.length > 0);
}

/** The audio tracks to list, none when the file has only one. */
export function audioRows(tracks: Tracks, on: number | null): TrackRow[] {
    if (tracks.audio.length < 2) return [];
    return tracks.audio.map((track) => ({ index: track.index, label: track.label, on: track.index === on }));
}

/** Off, then the subtitle tracks; none when the file has no subtitles. */
export function subtitleRows(tracks: Tracks, on: number | null): TrackRow[] {
    if (tracks.subtitles.length === 0) return [];
    const rows = tracks.subtitles.map((track) => ({ index: track.index as number | null, label: track.label, on: track.index === on }));
    return [{ index: null, label: 'Off', on: on === null || rows.every((row) => !row.on) }, ...rows];
}

/** The track the stream has to carry for subtitle `index`: a picture track, which has no text to draw here. */
export function drawnIn(tracks: Tracks | null, index: number | null): number | null {
    const track = tracks?.subtitles.find((one) => one.index === index);
    return track?.picture ? track.index : null;
}

/** What the C key switches to: off while subtitles show, else the ones shown last, else the file's first. */
export function toggledSubtitle(tracks: Tracks | null, on: number | null, last: number | null): number | null {
    if (on !== null) return null;
    const known = tracks?.subtitles ?? [];
    return known.find((track) => track.index === last)?.index ?? known[0]?.index ?? null;
}

export interface TimedCue {
    start: number;
    end: number;
    text: string;
}

/**
 * Cues in the stream's own seconds. A converted stream starts `offsetMs` into the item, so a cue over by then is
 * dropped and one under way starts with the stream.
 */
export function cueTimes(cues: readonly { startMs: number; endMs: number; text: string }[], offsetMs: number): TimedCue[] {
    const timed: TimedCue[] = [];
    for (const cue of cues) {
        const end = (cue.endMs - offsetMs) / 1000;
        if (end <= 0) continue;
        timed.push({ start: Math.max(0, (cue.startMs - offsetMs) / 1000), end, text: cue.text });
    }
    return timed;
}
