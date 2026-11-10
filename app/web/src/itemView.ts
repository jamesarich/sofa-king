import type { WebItem, WebItemDetail, WebItemState } from '@sofaking/core';
import { button, h, replaceChildren } from './dom';
import { focusFirst, keepFocus } from './focus';
import { length, position } from './format';
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
            replaceChildren(el, page(ctx, state.detail));
        });
        if (!focused && state.state === 'ready') {
            focused = true;
            focusFirst(el, '[data-primary]');
        }
    };
    watches.add(ctx.core.library.watchItem(itemId, draw));
    return { el, dispose: () => watches.cancel() };
}

function page(ctx: Context, detail: WebItemDetail): HTMLElement {
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

    const facts = [item.episodeLabel, item.year ? String(item.year) : '', length(item.durationMs), item.kind === 'movie' ? '' : item.kind, item.unreadable ? 'Unreadable' : '']
        .filter(Boolean)
        .join(' · ');
    return h(
        'div',
        {
            class: 'detail',
            style: item.backdrop
                ? { 'background-image': `linear-gradient(#14111Fcc,#14111F),url("${item.backdrop}")` }
                : {},
        },
        h('h1', { text: item.showTitle ? `${item.showTitle}: ${item.title}` : item.title }),
        h('p', { class: 'facts', text: facts }),
        detail.tagline ? h('p', { class: 'tagline', text: detail.tagline }) : null,
        h('div', { class: 'actions' }, play, again, watched),
        item.overview ? h('p', { class: 'overview', text: item.overview }) : null,
        detail.genres.length ? h('p', { class: 'facts', text: detail.genres.join(', ') }) : null,
        detail.directors.length ? h('p', { class: 'facts', text: `Directed by ${detail.directors.join(', ')}` }) : null,
        detail.cast.length ? h('p', { class: 'facts', text: `With ${detail.cast.slice(0, 8).join(', ')}` }) : null,
        detail.children.length
            ? h('div', { class: 'shelf' }, h('h2', { text: item.kind === 'show' ? 'Seasons' : 'Episodes' }), h('div', { class: 'cards' }, ...detail.children.map((child) => card(child, 'child'))))
            : null,
        detail.related.length
            ? h('div', { class: 'shelf' }, h('h2', { text: 'More like this' }), h('div', { class: 'cards' }, ...detail.related.map((related) => card(related, 'related'))))
            : null,
    );
}
