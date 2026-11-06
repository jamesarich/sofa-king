// What the live player's keys mean, as plain functions and small classes over the lineup, so they run without a
// browser. The timings match the Compose player (`ZAP_SETTLE_MS`, `DIGIT_COMMIT_MS`, `NUMBER_MISS_MS`).

/** How long a zap waits for another press before it tunes. */
export const ZAP_SETTLE_MS = 400;
/** How long typed digits wait for another before they tune. */
export const DIGIT_COMMIT_MS = 1_500;
/** How long "No channel N" shows. */
export const NUMBER_MISS_MS = 2_000;
/** How long the channel banner stays up. */
export const BANNER_MS = 4_000;
/** The most digits a channel number takes. */
export const MAX_DIGITS = 5;
/** One volume key press, as a fraction of full volume. */
export const VOLUME_STEP = 0.05;

export type LiveKey =
    | { kind: 'zap'; step: 1 | -1 }
    | { kind: 'digit'; digit: number }
    | { kind: 'commit' }
    | { kind: 'last' }
    | { kind: 'mute' }
    | { kind: 'volume'; step: 1 | -1 }
    | { kind: 'captions' }
    | { kind: 'fullscreen' }
    | { kind: 'pause' };

/**
 * The live player's key table. Up and Down are the channel above and below in the guide, Page Up and Page Down the
 * next higher and lower number, as on a remote; Left and Right stay with focus. Zap and Space act only on the picture,
 * so a focused button keeps its arrows and its Space.
 */
export function liveKey(key: string, onPicture: boolean): LiveKey | null {
    if (key >= '0' && key <= '9' && key.length === 1) return { kind: 'digit', digit: Number(key) };
    switch (key) {
        case 'ArrowUp':
            return onPicture ? { kind: 'zap', step: -1 } : null;
        case 'ArrowDown':
            return onPicture ? { kind: 'zap', step: 1 } : null;
        case 'PageUp':
            return { kind: 'zap', step: 1 };
        case 'PageDown':
            return { kind: 'zap', step: -1 };
        case 'Enter':
            return onPicture ? { kind: 'commit' } : null;
        case 'l':
        case 'L':
            return { kind: 'last' };
        case 'm':
        case 'M':
            return { kind: 'mute' };
        case '+':
        case '=':
            return { kind: 'volume', step: 1 };
        case '-':
            return { kind: 'volume', step: -1 };
        case 'c':
        case 'C':
            return { kind: 'captions' };
        case 'f':
        case 'F':
            return { kind: 'fullscreen' };
        case ' ':
            return onPicture ? { kind: 'pause' } : null;
        default:
            return null;
    }
}

/** The channel [step] places from [fromId] in [channels], wrapping at the ends; the first one when [fromId] is not there. */
export function neighbor<C extends { id: string }>(channels: readonly C[], fromId: string, step: number): C | null {
    if (channels.length === 0) return null;
    const index = channels.findIndex((channel) => channel.id === fromId);
    if (index < 0) return channels[0] ?? null;
    return channels[(((index + step) % channels.length) + channels.length) % channels.length] ?? null;
}

/** The channel whose displayed number is [digits], or null. */
export function channelForDigits<C extends { number: number }>(channels: readonly C[], digits: string): C | null {
    if (!digits) return null;
    return channels.find((channel) => String(channel.number) === digits) ?? null;
}

/** [volume] moved by [step] presses, kept between 0 and 1 and off the floating-point drift of repeated 0.05s. */
export function stepVolume(volume: number, step: number): number {
    return Math.min(1, Math.max(0, Math.round((volume + step * VOLUME_STEP) * 100) / 100));
}

export interface Timers {
    set(run: () => void, ms: number): unknown;
    clear(handle: unknown): void;
}

export const realTimers: Timers = {
    set: (run, ms) => setTimeout(run, ms),
    clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

/** Shows something and hides it again [ms] after the last [show]. */
export class Fade {
    private handle: unknown = null;

    constructor(
        private readonly setShown: (shown: boolean) => void,
        private readonly ms: number,
        private readonly timers: Timers = realTimers,
    ) {}

    show(): void {
        this.cancel();
        this.setShown(true);
        this.handle = this.timers.set(() => {
            this.handle = null;
            this.setShown(false);
        }, this.ms);
    }

    cancel(): void {
        if (this.handle !== null) this.timers.clear(this.handle);
        this.handle = null;
    }
}

/**
 * Digits typed for a channel number. They commit to [onCommit] [DIGIT_COMMIT_MS] after the last one or on [commit], and
 * [onChange] hears the buffer as it grows and when it clears.
 */
export class DigitEntry {
    digits = '';
    private handle: unknown = null;

    constructor(
        private readonly onChange: (digits: string) => void,
        private readonly onCommit: (digits: string) => void,
        private readonly timers: Timers = realTimers,
    ) {}

    get active(): boolean {
        return this.digits !== '';
    }

    append(digit: number): void {
        if (this.digits.length >= MAX_DIGITS) return;
        this.digits += String(digit);
        this.onChange(this.digits);
        this.cancelTimer();
        this.handle = this.timers.set(() => this.commit(), DIGIT_COMMIT_MS);
    }

    /** Hands the typed digits over and clears them first, so the handler may start over. */
    commit(): void {
        this.cancelTimer();
        const pending = this.digits;
        if (!pending) return;
        this.digits = '';
        this.onChange('');
        this.onCommit(pending);
    }

    cancel(): void {
        this.cancelTimer();
        this.digits = '';
    }

    private cancelTimer(): void {
        if (this.handle !== null) this.timers.clear(this.handle);
        this.handle = null;
    }
}

/**
 * Channel up and down presses that settle before they tune: each press moves the target from the last one, and
 * [onSettle] gets the final target [ZAP_SETTLE_MS] after the last press, so a run of presses opens one session.
 */
export class ZapSettle<C extends { id: string }> {
    private target: C | null = null;
    private handle: unknown = null;

    constructor(
        private readonly onTarget: (channel: C) => void,
        private readonly onSettle: (channel: C) => void,
        private readonly timers: Timers = realTimers,
    ) {}

    /** The channel [step] places from the pending target, or from [fromId] when nothing is pending. */
    press(channels: readonly C[], fromId: string, step: number): void {
        const next = neighbor(channels, this.target?.id ?? fromId, step);
        if (!next) return;
        this.target = next;
        this.onTarget(next);
        this.cancelTimer();
        this.handle = this.timers.set(() => {
            this.handle = null;
            this.target = null;
            this.onSettle(next);
        }, ZAP_SETTLE_MS);
    }

    cancel(): void {
        this.cancelTimer();
        this.target = null;
    }

    private cancelTimer(): void {
        if (this.handle !== null) this.timers.clear(this.handle);
        this.handle = null;
    }
}

/** The channel watched before the one on now, so a key returns to it, also after the guide picked another. */
export class ChannelHistory {
    private current: string | null = null;
    private before: string | null = null;

    /** Notes that [id] is on now. */
    watching(id: string): void {
        if (id === this.current) return;
        this.before = this.current;
        this.current = id;
    }

    get last(): string | null {
        return this.before;
    }
}

export interface BannerText {
    heading: string;
    now: string;
    next: string;
}

/** The lines of the channel banner at [atMs]: the channel, what is on with its hours, and what follows. */
export function bannerText(
    channel: { number: number; name: string },
    atMs: number,
    nowNext: { now: { title: string; startMs: number; stopMs: number } | null; next: { title: string } | null },
    clock: (ms: number) => string,
): BannerText {
    return {
        heading: `${channel.number} ${channel.name}`,
        now: nowNext.now ? `${nowNext.now.title}, ${clock(nowNext.now.startMs)} to ${clock(nowNext.now.stopMs)}` : '',
        next: nowNext.next ? `Next: ${nowNext.next.title}` : '',
    };
}
