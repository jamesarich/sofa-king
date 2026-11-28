import type { WebGrid, WebHome, WebItem, WebSpotlight } from '@sofaking/core';
import { button, h, replaceChildren } from './dom';
import { focusFirst, keepFocus } from './focus';
import { length } from './format';
import { homeStatus, isSignedOut } from './messages';
import { showsLetters, titleCount } from './letters';
import { extendsShown, loadsItself, moreLabel, NEAR_END_PX, nearEnd } from './paging';
import { href } from './router';
import { signedOutNotice } from './signedOut';
import { Watches, type Context, type View } from './view';

/** What [card] shows of [item]: a card whose key is unchanged need not be drawn again. */
function cardKey(item: WebItem): string {
    return JSON.stringify([item.id, item.title, item.showTitle, item.episodeLabel, item.year, item.durationMs, item.poster, item.progress, item.unreadable]);
}

/** A card for [item], which opens its page. */
export function card(item: WebItem, keyPrefix: string): HTMLElement {
    const detail = [item.episodeLabel, item.year ? String(item.year) : '', length(item.durationMs), item.unreadable ? 'Unreadable' : ''].filter(Boolean).join(' · ');
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

/**
 * What the home leads with: the picture, title and first sentence of the item Continue watching resumes or, without
 * one, the newest addition, with Resume or Play and the way to its page.
 */
function spotlight(lead: WebSpotlight): HTMLElement {
    const item = lead.item;
    const facts = [item.showTitle ? item.title : '', item.episodeLabel, item.year ? String(item.year) : '', length(item.durationMs)].filter(Boolean).join(' · ');
    return h(
        'div',
        {
            class: 'spotlight',
            style: item.backdrop ? { 'background-image': `linear-gradient(90deg,#14111Fee,#14111F99 45%,#14111F22),url("${item.backdrop}")` } : {},
        },
        h('h1', { text: item.showTitle ?? item.title }),
        facts ? h('p', { class: 'facts', text: facts }) : null,
        lead.tagline ? h('p', { class: 'overview', text: lead.tagline }) : null,
        h(
            'div',
            { class: 'actions' },
            item.playable && !item.unreadable
                ? h('a', {
                      class: 'button primary',
                      text: lead.resumes ? 'Resume' : 'Play',
                      attrs: { href: href({ page: 'play', id: item.id, fromStart: false }), 'data-key': 'lead:play' },
                  })
                : null,
            h('a', { class: 'button', text: 'Details', attrs: { href: href({ page: 'item', id: item.id }), 'data-key': 'lead:open' } }),
        ),
    );
}

/** The library home: what it leads with, the libraries to open and its shelves. */
export function libraryView(ctx: Context): View {
    const watches = new Watches();
    const el = h('section', { class: 'library' });
    let focused = false;
    const draw = (home: WebHome) => {
        keepFocus(el, () => {
            if (isSignedOut(home)) {
                replaceChildren(el, signedOutNotice(true));
                return;
            }
            if (home.state !== 'ready') {
                replaceChildren(el, h('p', { class: 'status', text: homeStatus(home) }));
                return;
            }
            replaceChildren(
                el,
                home.spotlight ? spotlight(home.spotlight) : null,
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
        if (!focused && (home.state === 'ready' || isSignedOut(home))) {
            focused = true;
            focusFirst(el);
        }
    };
    watches.add(ctx.core.library.watchHome(draw));
    return { el, dispose: () => watches.cancel() };
}

const SORTS: Array<[string, string]> = [
    ['title', 'Title'],
    ['added', 'Recently added'],
    ['year', 'Year'],
    ['rating', 'Rating'],
    ['watched', 'Recently watched'],
];

/** One library's grid, in the order chosen; the next page loads as the reader nears the end. */
export function gridView(ctx: Context, libraryId: string): View {
    const watches = new Watches();
    const sort = h('select', { attrs: { 'aria-label': 'Sort by' } }, ...SORTS.map(([value, label]) => h('option', { text: label, attrs: { value } })));
    const unwatched = h('input', { attrs: { type: 'checkbox' } });
    const genre = h('select', { attrs: { 'aria-label': 'Genre' } });
    // Shown only in a library that has collections.
    const collection = h('select', { attrs: { 'aria-label': 'Collection' } });
    collection.hidden = true;
    const cards = h('div', { class: 'cards grid' });
    const more = h('button', { class: 'more', text: 'Load more', attrs: { type: 'button' }, on: { click: () => ctx.core.library.loadMore() } });
    const status = h('p', { class: 'status', attrs: { role: 'status' } });
    const count = h('span', { class: 'count' });
    const letters = h('nav', { class: 'letters', attrs: { 'aria-label': 'Jump to a letter' } });
    letters.hidden = true;
    // The grid's end, which the page watches come near.
    const end = h('div', { class: 'grid-end', attrs: { 'aria-hidden': 'true' } });
    more.hidden = true;
    const el = h(
        'section',
        { class: 'library' },
        h('div', { class: 'bar' }, sort, genre, collection, h('label', { class: 'check' }, unwatched, ' Unwatched only'), count),
        letters,
        cards,
        status,
        more,
        end,
    );
    const open = () => ctx.core.library.openGrid(libraryId, sort.value, unwatched.checked, genre.value || null, collection.value || null);
    sort.addEventListener('change', open);
    unwatched.addEventListener('change', open);
    genre.addEventListener('change', open);
    collection.addEventListener('change', open);

    let shown: WebGrid | null = null;
    // The cards drawn, by what each shows, so a page that follows them adds its own and leaves the rest in place.
    let drawn: string[] = [];
    const page = () => {
        if (shown && loadsItself(shown, nearEnd(end.getBoundingClientRect().top, window.innerHeight))) ctx.core.library.loadMore();
    };
    // Scrolling the end near loads the next page; a browser without the observer keeps the button.
    const watcher = typeof IntersectionObserver === 'undefined' ? null : new IntersectionObserver(page, { rootMargin: `${NEAR_END_PX}px 0px` });
    watcher?.observe(end);

    let focused = false;
    let genresKey = '';
    let collectionsKey = '';
    let lettersKey = '';
    // The item a letter jump is loading toward, scrolled to once the grid holds it.
    let jumpTo: number | null = null;
    const jump = (offset: number) => {
        jumpTo = offset;
        ctx.core.library.loadThrough(offset);
        arrive();
    };
    const arrive = () => {
        const target = jumpTo === null ? undefined : cards.children[jumpTo];
        if (!(target instanceof HTMLElement)) return;
        jumpTo = null;
        target.scrollIntoView({ block: 'start' });
        target.focus();
    };
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
            const names = grid.collections.join('|');
            if (names !== collectionsKey) {
                collectionsKey = names;
                const chosen = collection.value;
                replaceChildren(collection, h('option', { text: 'All collections', attrs: { value: '' } }), ...grid.collections.map((name) => h('option', { text: name, attrs: { value: name } })));
                collection.value = chosen;
                collection.hidden = grid.collections.length === 0;
            }
            const row = grid.letters.map((entry) => ({ letter: entry.letter, offset: entry.offset }));
            const rowKey = row.map((entry) => `${entry.letter}${entry.offset}`).join('|');
            letters.hidden = !showsLetters(sort.value, grid.total, row);
            if (rowKey !== lettersKey) {
                lettersKey = rowKey;
                replaceChildren(
                    letters,
                    ...row.map((entry) => button(entry.letter, () => jump(entry.offset), 'letter')),
                );
            }
            count.textContent = grid.total > 0 ? titleCount(grid.total) : '';
            const keys = grid.items.map(cardKey);
            if (extendsShown(drawn, keys)) cards.append(...grid.items.slice(drawn.length).map((item) => card(item, 'grid')));
            else replaceChildren(cards, ...grid.items.map((item) => card(item, 'grid')));
            drawn = keys;
            arrive();
            const label = moreLabel(grid, watcher !== null);
            more.hidden = label === null;
            if (label) more.textContent = label;
            status.textContent = grid.error ? `Some of it did not load: ${grid.error}` : grid.loading ? 'Loading' : grid.items.length === 0 ? 'Nothing here.' : '';
        });
        if (!focused && grid.items.length > 0) {
            focused = true;
            focusFirst(cards);
        }
        // A page that leaves the end near, as the first does in a tall window, is followed by the next.
        shown = grid;
        requestAnimationFrame(page);
    };
    watches.add(ctx.core.library.watchGrid(draw));
    open();
    return {
        el,
        dispose() {
            watcher?.disconnect();
            shown = null;
            watches.cancel();
        },
    };
}
