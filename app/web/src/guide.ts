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
