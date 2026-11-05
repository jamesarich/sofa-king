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
