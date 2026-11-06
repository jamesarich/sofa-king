// Pages by hash, since the server serves one file under /app2/: #/guide, #/watch/ID, #/library, #/library/ID,
// #/item/ID, #/play/ID, #/settings.

export type Route =
    | { page: 'guide' }
    | { page: 'watch'; id: string }
    | { page: 'library' }
    | { page: 'grid'; id: string }
    | { page: 'item'; id: string }
    | { page: 'play'; id: string; fromStart: boolean }
    | { page: 'settings' };

export function parseRoute(hash: string): Route {
    const [path = '', query = ''] = hash.replace(/^#\/?/, '').split('?');
    const [page = '', ...rest] = path.split('/');
    const id = decodeURIComponent(rest.join('/'));
    switch (page) {
        case 'watch':
            return id ? { page: 'watch', id } : { page: 'guide' };
        case 'library':
            return id ? { page: 'grid', id } : { page: 'library' };
        case 'item':
            return id ? { page: 'item', id } : { page: 'library' };
        case 'play':
            return id ? { page: 'play', id, fromStart: new URLSearchParams(query).has('start') } : { page: 'library' };
        case 'settings':
            return { page: 'settings' };
        default:
            return { page: 'guide' };
    }
}

/** What the app writes into `history.state` of each entry it is on: how many of its own pages lie below. */
export type Stamp = { sk: number };

function stamped(state: unknown): state is Stamp {
    return typeof state === 'object' && state !== null && Number.isInteger((state as Stamp).sk) && (state as Stamp).sk >= 0;
}

/** The stamp for an entry just reached: its own when it has one, else one deeper than the entry it came from. */
export function stampFor(state: unknown, previous: number): Stamp {
    return stamped(state) ? state : { sk: previous + 1 };
}

/** Back leaves the page only for an entry the app made; the first entry, or one it did not stamp, goes to the guide. */
export function backStaysInApp(state: unknown): boolean {
    return stamped(state) && state.sk > 0;
}

export function href(route: Route): string {
    switch (route.page) {
        case 'guide':
            return '#/guide';
        case 'watch':
            return `#/watch/${encodeURIComponent(route.id)}`;
        case 'library':
            return '#/library';
        case 'grid':
            return `#/library/${encodeURIComponent(route.id)}`;
        case 'item':
            return `#/item/${encodeURIComponent(route.id)}`;
        case 'play':
            return `#/play/${encodeURIComponent(route.id)}${route.fromStart ? '?start' : ''}`;
        case 'settings':
            return '#/settings';
    }
}
