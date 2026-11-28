// What the guide shows of the lineup: its chips, the channels each keeps and what is on now and next. Plain functions
// over the lineup core hands over, so they run without a browser.

export interface ProgramLike {
    title: string;
    startMs: number;
    stopMs: number;
}

export interface ChannelLike<P extends ProgramLike = ProgramLike> {
    id: string;
    number: number;
    name: string;
    groups: readonly string[];
    programs: readonly P[];
}

export interface GuidePrefs {
    favorites: readonly string[];
    recentChannels: readonly string[];
}

export interface Chip {
    /** What the setting remembers; null is All. */
    key: string | null;
    label: string;
}

const GROUP = 'group:';

/** All, then Favorites and Recent when there are any, then each group the channels are filed under, by name. */
export function chipsFor(channels: readonly ChannelLike[], prefs: GuidePrefs): Chip[] {
    const chips: Chip[] = [{ key: null, label: 'All' }];
    const known = new Set(channels.map((channel) => channel.id));
    if (prefs.favorites.some((id) => known.has(id))) chips.push({ key: 'favorites', label: 'Favorites' });
    if (prefs.recentChannels.some((id) => known.has(id))) chips.push({ key: 'recent', label: 'Recent' });
    const groups = new Set(channels.flatMap((channel) => channel.groups));
    for (const name of [...groups].sort((a, b) => a.localeCompare(b))) {
        chips.push({ key: GROUP + name, label: name });
    }
    return chips;
}

/** More groups than this go in one menu instead of a chip each. */
export const GROUP_CHIPS_MAX = 8;

/**
 * [chips] as the guide's bar draws them: a chip each while the groups are few, else All, Favorites and Recent as
 * chips and the groups in a menu.
 */
export function chipBar(chips: readonly Chip[], max = GROUP_CHIPS_MAX): { chips: Chip[]; menu: Chip[] } {
    const groups = chips.filter((chip) => chip.key?.startsWith(GROUP));
    if (groups.length <= max) return { chips: [...chips], menu: [] };
    return { chips: chips.filter((chip) => !chip.key?.startsWith(GROUP)), menu: groups };
}

/** The channels [key] keeps, by number; Recent keeps the order the channels were watched in. A search narrows by name. */
export function filterChannels<C extends ChannelLike>(
    channels: readonly C[],
    key: string | null,
    prefs: GuidePrefs,
    search = '',
): C[] {
    let kept: C[];
    if (key === 'favorites') {
        const favorites = new Set(prefs.favorites);
        kept = channels.filter((channel) => favorites.has(channel.id));
    } else if (key === 'recent') {
        const byId = new Map(channels.map((channel) => [channel.id, channel]));
        kept = prefs.recentChannels.flatMap((id) => byId.get(id) ?? []);
    } else if (key !== null && key.startsWith(GROUP)) {
        const name = key.slice(GROUP.length);
        kept = channels.filter((channel) => channel.groups.includes(name));
    } else {
        kept = [...channels];
    }
    if (key !== 'recent') kept.sort((a, b) => a.number - b.number);
    const needle = search.trim().toLowerCase();
    if (!needle) return kept;
    return kept.filter(
        (channel) =>
            channel.name.toLowerCase().includes(needle) ||
            String(channel.number) === needle ||
            channel.programs.some((program) => program.title.toLowerCase().includes(needle)),
    );
}

export interface NowNext<P extends ProgramLike = ProgramLike> {
    now: P | null;
    next: P | null;
}

export function nowNext<P extends ProgramLike>(channel: ChannelLike<P>, atMs: number): NowNext<P> {
    const programs = [...channel.programs].sort((a, b) => a.startMs - b.startMs);
    const index = programs.findIndex((program) => program.startMs <= atMs && program.stopMs > atMs);
    if (index < 0) return { now: null, next: programs.find((program) => program.startMs > atMs) ?? null };
    return { now: programs[index] ?? null, next: programs[index + 1] ?? null };
}

/** How far through [program] it is at [atMs], 0 to 1. */
export function elapsed(program: ProgramLike, atMs: number): number {
    const length = program.stopMs - program.startMs;
    return length <= 0 ? 0 : Math.min(1, Math.max(0, (atMs - program.startMs) / length));
}

export function clock(ms: number, locale?: string): string {
    return new Date(ms).toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit' });
}

// The grid: every channel's programs laid out on one timeline, from the half hour now is in to six hours ahead, which
// is as far as core hands programs over.

export const GRID_HOURS = 6;
export const SLOT_MINUTES = 30;
/** The width of a minute in the grid, in CSS pixels: a half hour is 180 wide. */
export const PX_PER_MINUTE = 6;

/** The start of the grid's window: the half hour [atMs] is in, by the wall clock. */
export function gridStart(atMs: number): number {
    const start = new Date(atMs);
    start.setMinutes(start.getMinutes() < SLOT_MINUTES ? 0 : SLOT_MINUTES, 0, 0);
    return start.getTime();
}

/** The start of each half hour in a window of [hours] from [startMs], for the row of times. */
export function gridSlots(startMs: number, hours = GRID_HOURS): number[] {
    return Array.from({ length: (hours * 60) / SLOT_MINUTES }, (_, index) => startMs + index * SLOT_MINUTES * 60_000);
}

/** How far [ms] of time reaches across the grid, in CSS pixels. */
export function gridPx(ms: number): number {
    return (ms / 60_000) * PX_PER_MINUTE;
}

/** One cell of a channel's row: a program, or a stretch the guide lists nothing for ([program] null). */
export interface GridCell<P extends ProgramLike = ProgramLike> {
    program: P | null;
    startMs: number;
    stopMs: number;
}

/**
 * A channel's row from [startMs] to [endMs]: its programs in order, each cut to the window and to the end of the one
 * before it, with a gap wherever the guide lists nothing, so the cells fill the row without overlapping.
 */
export function gridCells<P extends ProgramLike>(programs: readonly P[], startMs: number, endMs: number): GridCell<P>[] {
    const cells: GridCell<P>[] = [];
    let cursor = startMs;
    for (const program of [...programs].sort((a, b) => a.startMs - b.startMs)) {
        if (cursor >= endMs) break;
        if (program.stopMs <= cursor || program.startMs >= endMs) continue;
        if (program.startMs > cursor) cells.push({ program: null, startMs: cursor, stopMs: program.startMs });
        const from = Math.max(program.startMs, cursor);
        const to = Math.min(program.stopMs, endMs);
        cells.push({ program, startMs: from, stopMs: to });
        cursor = to;
    }
    if (cursor < endMs) cells.push({ program: null, startMs: cursor, stopMs: endMs });
    return cells;
}

/** The line under a program's title in its details: "8:00 PM to 9:00 PM · 5 News 5". */
export function programWhen(program: ProgramLike, channel: { number: number; name: string }, locale?: string): string {
    return `${clock(program.startMs, locale)} to ${clock(program.stopMs, locale)} · ${channel.number} ${channel.name}`;
}
