// What the search page says about what it found: its rows' titles and when a program airs. Plain functions over what
// core hands over, so they run without a browser.

/** Long enough that typing a word asks once, not per letter; the Compose apps wait as long. */
export const SEARCH_DEBOUNCE_MS = 400;

export const SEARCH_HINT = 'Channel, program, title or cast';

const ROW_TITLES: Record<string, string> = {
    movies: 'Movies',
    shows: 'Shows',
    episodes: 'Episodes',
    videos: 'Videos',
    artists: 'Artists',
    albums: 'Albums',
    tracks: 'Songs',
};

/** The row of titles found by their cast or directors names those people, up to two, or else the query. */
export function peopleTitle(people: readonly string[], query: string): string {
    const [first, second] = people;
    if (first === undefined) return `With ${query}`;
    if (second === undefined) return `With ${first}`;
    return people.length === 2 ? `With ${first} and ${second}` : `With ${first}, ${second} and others`;
}

/** The heading over a library row of [group], as core names it. */
export function rowTitle(group: string, people: readonly string[], query: string): string {
    if (group === 'cast_and_crew') return peopleTitle(people, query);
    return ROW_TITLES[group] ?? group;
}

function sameDay(a: Date, b: Date): boolean {
    return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** When a program that starts at [startMs] airs, seen at [nowMs]: "Today 8:00 PM", "Tomorrow 9:30 AM" or "Sat 7:00 PM". */
export function airingWhen(startMs: number, nowMs: number, locale?: string): string {
    const start = new Date(startMs);
    const now = new Date(nowMs);
    const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
    const day = sameDay(start, now) ? 'Today' : sameDay(start, next) ? 'Tomorrow' : start.toLocaleDateString(locale, { weekday: 'short' });
    return `${day} ${start.toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit' })}`;
}

export interface FoundLike {
    now: readonly unknown[];
    rows: readonly unknown[];
    later: readonly unknown[];
}

/** What the page says under the field: nothing over results, a word while it waits, and what did not match. */
export function searchStatus(query: string, searching: boolean, found: FoundLike | null): string {
    if (!query.trim()) return 'Search the channels, the programs on now and coming up, and the libraries.';
    if (searching || !found) return 'Searching';
    return found.now.length + found.rows.length + found.later.length > 0 ? '' : `Nothing matches "${query.trim()}".`;
}
