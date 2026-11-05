import type { WebGrid, WebHome, WebItem } from '@sofaking/core';
import { h, replaceChildren } from './dom';
import { focusFirst, keepFocus } from './focus';
import { length } from './format';
import { href } from './router';
import { Watches, type Context, type View } from './view';

/** A card for [item], which opens its page. */
export function card(item: WebItem, keyPrefix: string): HTMLElement {
    const detail = [item.episodeLabel, item.year ? String(item.year) : '', length(item.durationMs)].filter(Boolean).join(' · ');
    return h(
        'a',
        { class: 'card', attrs: { href: href({ page: 'item', id: item.id }), 'data-key': `${keyPrefix}:${item.id}` } },
        item.poster
            ? h('img', { class: 'poster', attrs: { src: item.poster, alt: '', loading: 'lazy' } })
            : h('span', { class: 'poster blank', text: item.title.slice(0, 1) }),
        h('span', { class: 'card-title', text: item.showTitle ?? item.title }),
        h('span', { class: 'card-detail', text: item.showTitle ? item.title : detail }),
        item.progress != null
            ? h('span', { class: 'meter' }, h('span', { class: 'fill', style: { width: `${Math.round(item.progress * 100)}%` } }))
            : null,
    );
}

/** The library home: its shelves and the libraries to open. */
export function libraryView(ctx: Context): View {
    const watches = new Watches();
    const el = h('section', { class: 'library' });
    let focused = false;
    const draw = (home: WebHome) => {
        keepFocus(el, () => {
            if (home.state !== 'ready') {
                replaceChildren(el, h('p', { class: 'status', text: homeMessage(home) }));
                return;
            }
            replaceChildren(
                el,
                h(
                    'div',
                    { class: 'chips' },
                    ...home.libraries.map((library) =>
                        h('a', {
                            class: 'chip',
                            text: `${library.name} (${library.itemCount})`,
                            attrs: { href: href({ page: 'grid', id: library.id }), 'data-key': `lib:${library.id}` },
                        }),
                    ),
                ),
                ...home.shelves.map((shelf) =>
                    h(
                        'div',
                        { class: 'shelf' },
                        h('h2', { text: shelf.title }),
                        h('div', { class: 'cards' }, ...shelf.items.map((item) => card(item, shelf.key))),
                    ),
                ),
            );
        });
        if (!focused && home.state === 'ready') {
            focused = true;
            focusFirst(el);
        }
    };
    watches.add(ctx.core.library.watchHome(draw));
    return { el, dispose: () => watches.cancel() };
}

function homeMessage(home: WebHome): string {
    switch (home.state) {
        case 'unconfigured':
            return 'No server yet. Open Settings to pair with one.';
        case 'unavailable':
            return 'This server keeps no libraries.';
        case 'failed':
            return `The library did not load: ${home.message}`;
        default:
            return 'Loading the library';
    }
}

const SORTS: Array<[string, string]> = [
    ['title', 'Title'],
    ['added', 'Recently added'],
    ['year', 'Year'],
    ['rating', 'Rating'],
    ['watched', 'Recently watched'],
];

/** One library's grid, in the order chosen, a page at a time. */
export function gridView(ctx: Context, libraryId: string): View {
    const watches = new Watches();
    const sort = h('select', { attrs: { 'aria-label': 'Sort by' } }, ...SORTS.map(([value, label]) => h('option', { text: label, attrs: { value } })));
    const unwatched = h('input', { attrs: { type: 'checkbox' } });
    const genre = h('select', { attrs: { 'aria-label': 'Genre' } });
    const cards = h('div', { class: 'cards grid' });
    const more = h('button', { class: 'more', text: 'Load more', attrs: { type: 'button' }, on: { click: () => ctx.core.library.loadMore() } });
    const status = h('p', { class: 'status', attrs: { role: 'status' } });
    const el = h(
        'section',
        { class: 'library' },
        h('div', { class: 'bar' }, sort, genre, h('label', { class: 'check' }, unwatched, ' Unwatched only')),
        cards,
        status,
        more,
    );
    const open = () => ctx.core.library.openGrid(libraryId, sort.value, unwatched.checked, genre.value || null);
    sort.addEventListener('change', open);
    unwatched.addEventListener('change', open);
    genre.addEventListener('change', open);

    let focused = false;
    let genresKey = '';
    const draw = (grid: WebGrid | null | undefined) => {
        if (!grid || grid.libraryId !== libraryId) return;
        keepFocus(el, () => {
            const key = grid.genres.join('|');
            if (key !== genresKey) {
                genresKey = key;
                const chosen = genre.value;
                replaceChildren(genre, h('option', { text: 'All genres', attrs: { value: '' } }), ...grid.genres.map((name) => h('option', { text: name, attrs: { value: name } })));
                genre.value = chosen;
            }
            replaceChildren(cards, ...grid.items.map((item) => card(item, 'grid')));
            more.hidden = !grid.hasMore;
            status.textContent = grid.error ? `Some of it did not load: ${grid.error}` : grid.loading ? 'Loading' : grid.items.length === 0 ? 'Nothing here.' : '';
        });
        if (!focused && grid.items.length > 0) {
            focused = true;
            focusFirst(cards);
        }
    };
    watches.add(ctx.core.library.watchGrid(draw));
    open();
    return { el, dispose: () => watches.cancel() };
}
