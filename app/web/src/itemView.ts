import type { WebCastPerson, WebItem, WebItemDetail, WebItemState } from '@sofaking/core';
import { button, h, replaceChildren } from './dom';
import { focusFirst, keepFocus } from './focus';
import { extraLabel, initials, length, position } from './format';
import { card } from './libraryView';
import { href } from './router';
import { Watches, type Context, type View } from './view';

/** One item's page: what it is, the way to play it, and what it holds (a show's seasons, a season's episodes). */
export function itemView(ctx: Context, itemId: string): View {
    const watches = new Watches();
    const el = h('section', { class: 'item' });
    let focused = false;

    const draw = (state: WebItemState) => {
        keepFocus(el, () => {
            if (state.state !== 'ready' || !state.detail) {
                replaceChildren(el, h('p', { class: 'status', text: state.state === 'failed' ? `This page did not load: ${state.message}` : 'Loading' }));
                return;
            }
            replaceChildren(el, page(ctx, state.detail, state.listed));
        });
        if (!focused && state.state === 'ready') {
            focused = true;
            focusFirst(el, '[data-primary]');
        }
    };
    watches.add(ctx.core.library.watchItem(itemId, draw));
    return { el, dispose: () => watches.cancel() };
}

function page(ctx: Context, detail: WebItemDetail, listed: boolean | null | undefined): HTMLElement {
    const item = detail.item;
    const target: WebItem | null = item.playable ? item : (detail.next ?? null);
    const resumes = target !== null && target.positionMs > 0 && !target.watched;
    const play = target
        ? h('a', {
              class: 'button primary',
              text: resumes ? `Resume at ${position(target.positionMs / 1000)}` : 'Play',
              attrs: { href: href({ page: 'play', id: target.id, fromStart: false }), 'data-primary': true, 'data-key': 'play' },
          })
        : null;
    const again = resumes
        ? h('a', { class: 'button', text: 'From the start', attrs: { href: href({ page: 'play', id: target.id, fromStart: true }), 'data-key': 'start' } })
        : null;
    const watched = button(item.watched ? 'Mark unwatched' : 'Mark watched', () => ctx.core.library.setWatched(item.id, !item.watched));
    watched.dataset.key = 'watched';
    // The household's watchlist, on a server that keeps one, for what can go on it.
    const watchlist = listed == null ? null : button(listed ? 'Remove from watchlist' : 'Add to watchlist', () => ctx.core.library.toggleWatchlist(item.id));
    if (watchlist) {
        watchlist.dataset.key = 'watchlist';
        watchlist.setAttribute('aria-pressed', String(listed));
    }

    // An episode's picture is a wide still, which a poster's frame would crop.
    const poster = item.kind === 'episode' ? null : item.poster;
    const facts = [item.episodeLabel, item.year ? String(item.year) : '', length(item.durationMs), ...detail.ratings.slice(0, RATINGS_SHOWN), item.kind === 'movie' ? '' : item.kind, item.unreadable ? 'Unreadable' : '']
        .filter(Boolean)
        .join(' · ');
    // With a role or a photo for any of the cast, each gets a card under the summary; the names alone stay a line in it.
    const cast = detail.people.some((one) => one.role || one.photo) ? detail.people.slice(0, CAST_SHOWN) : [];
    return h(
        'div',
        {
            class: 'detail',
            style: item.backdrop
                ? // The page's own color over the backdrop, so the text reads in the dark and the light scheme.
                  { 'background-image': `linear-gradient(color-mix(in srgb, var(--bg) 80%, transparent), var(--bg)),url("${item.backdrop}")` }
                : {},
        },
        h(
            'div',
            { class: 'summary' },
            poster ? h('img', { class: 'poster', attrs: { src: poster, alt: '' } }) : null,
            h('div', { class: 'summary-text' },
                h('h1', { text: item.showTitle ? `${item.showTitle}: ${item.title}` : item.title }),
                h('p', { class: 'facts', text: facts }),
                detail.tagline ? h('p', { class: 'tagline', text: detail.tagline }) : null,
                h('div', { class: 'actions' }, play, again, watched, watchlist),
                item.overview ? h('p', { class: 'overview', text: item.overview }) : null,
                detail.genres.length ? h('p', { class: 'facts', text: detail.genres.join(', ') }) : null,
                detail.directors.length ? h('p', { class: 'facts', text: `Directed by ${detail.directors.join(', ')}` }) : null,
                !cast.length && detail.cast.length ? h('p', { class: 'facts', text: `With ${detail.cast.slice(0, 8).join(', ')}` }) : null,
            ),
        ),
        cast.length ? h('div', { class: 'shelf' }, h('h2', { text: 'Cast' }), h('div', { class: 'cards' }, ...cast.map(person))) : null,
        detail.children.length
            ? h('div', { class: 'shelf' }, h('h2', { text: item.kind === 'show' ? 'Seasons' : 'Episodes' }), h('div', { class: 'cards' }, ...detail.children.map((child) => card(child, 'child'))))
            : null,
        detail.collectionItems.length
            ? h('div', { class: 'shelf' }, h('h2', { text: detail.collection ?? 'Collection' }), h('div', { class: 'cards' }, ...detail.collectionItems.map((other) => card(other, 'collection'))))
            : null,
        detail.extras.length ? h('div', { class: 'shelf' }, h('h2', { text: 'Extras' }), h('div', { class: 'cards' }, ...detail.extras.map(extra))) : null,
        detail.related.length
            ? h('div', { class: 'shelf' }, h('h2', { text: 'More like this' }), h('div', { class: 'cards' }, ...detail.related.map((related) => card(related, 'related'))))
            : null,
    );
}

const CAST_SHOWN = 12;
/** The most of an item's scores the line under its title names, in the order the server lists them. */
const RATINGS_SHOWN = 4;

/** A trailer, featurette or other extra: a wide card that plays it, named by what it is. */
function extra(item: WebItem): HTMLElement {
    return h(
        'a',
        { class: 'card extra', attrs: { href: href({ page: 'play', id: item.id, fromStart: true }), 'data-key': `extra:${item.id}` } },
        item.poster ? h('img', { class: 'still', attrs: { src: item.poster, alt: '', loading: 'lazy' } }) : h('span', { class: 'still blank', text: '▶', attrs: { 'aria-hidden': 'true' } }),
        h('span', { class: 'card-title', text: item.title }),
        h('span', { class: 'card-detail', text: [extraLabel(item.extra), length(item.durationMs)].filter(Boolean).join(' · ') }),
    );
}

/**
 * One of the cast: their photo, or their initials where there is none, over their name and the role they play. The
 * card searches for them, which lists the other titles they are in.
 */
function person(one: WebCastPerson): HTMLElement {
    return h(
        'a',
        { class: 'person', attrs: { href: href({ page: 'search', query: one.name }), 'data-key': `cast:${one.name}`, 'aria-label': `Search for ${one.name}` } },
        one.photo
            ? h('img', { class: 'photo', attrs: { src: one.photo, alt: '', loading: 'lazy' } })
            : h('span', { class: 'photo blank', text: initials(one.name), attrs: { 'aria-hidden': 'true' } }),
        h('span', { class: 'card-title', text: one.name }),
        one.role ? h('span', { class: 'card-detail', text: one.role }) : null,
    );
}
