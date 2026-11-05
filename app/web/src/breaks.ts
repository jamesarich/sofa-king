// The break overlay's state: hls.js hands the stream's date ranges over as it reads each playlist, core's break marks
// say when the picture is inside one, and the setting says what to do about it.

/** What the overlay needs of core's `WebBreakMarks`. */
export interface MarksLike {
    addLine(tag: string): void;
    at(wallClockMs: number): { remainingMs: number | null | undefined } | null | undefined;
}

/** A date range as hls.js parses it: its attributes as the playlist wrote them, by name. */
export interface DateRangeLike {
    attr: Record<string, unknown>;
}

export type LiveBreakMode = 'PLAY' | 'SCREEN_MUTED' | 'SCREEN';

export interface BreakView {
    /** The overlay covers the picture. */
    covered: boolean;
    /** The sound is off while it covers. */
    muted: boolean;
    /** How long the break has left, when the playlist says. */
    remainingMs: number | null;
    /** The viewer asked to see the picture through this break. */
    pictureShown: boolean;
}

export const NO_BREAK: BreakView = { covered: false, muted: false, remainingMs: null, pictureShown: false };

/** The `#EXT-X-DATERANGE` line for [range], the form core's parser reads. */
export function dateRangeLine(range: DateRangeLike): string {
    const parts: string[] = [];
    for (const [name, value] of Object.entries(range.attr)) {
        if (value === undefined || value === null) continue;
        const text = String(value);
        // Numbers (DURATION, PLANNED-DURATION) are bare in the playlist and strings are quoted.
        const bare = typeof value === 'number' || (name.endsWith('DURATION') && /^-?\d+(\.\d+)?$/.test(text));
        parts.push(bare ? `${name}=${text}` : `${name}="${text}"`);
    }
    return `#EXT-X-DATERANGE:${parts.join(',')}`;
}

export class BreakTracker {
    private shown = false;
    private mode: LiveBreakMode = 'PLAY';

    constructor(private readonly marks: MarksLike) {}

    setMode(mode: LiveBreakMode): void {
        this.mode = mode;
    }

    /** Takes the date ranges of a playlist the player read, which hls.js keys by their ids. */
    onRanges(ranges: Record<string, DateRangeLike | undefined> | undefined): void {
        for (const range of Object.values(ranges ?? {})) {
            if (range) this.marks.addLine(dateRangeLine(range));
        }
    }

    /** The viewer chose Show picture for the break now on. */
    showPicture(): void {
        this.shown = true;
    }

    /** The overlay at [wallClockMs], the playlist's clock at the picture; null while the player has no date yet. */
    update(wallClockMs: number | null): BreakView {
        const found = wallClockMs === null ? null : this.marks.at(wallClockMs);
        if (!found) {
            this.shown = false;
            return NO_BREAK;
        }
        const covers = this.mode !== 'PLAY';
        return {
            covered: covers && !this.shown,
            muted: this.mode === 'SCREEN_MUTED' && !this.shown,
            remainingMs: found.remainingMs ?? null,
            pictureShown: covers && this.shown,
        };
    }
}

/** `1:05` for 65 seconds, rounded up so the countdown reads 0:01 until it ends. */
export function countdown(remainingMs: number): string {
    const seconds = Math.max(0, Math.ceil(remainingMs / 1000));
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
