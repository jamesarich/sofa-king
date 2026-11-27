import type { WebLiveHit, WebSearchResults } from '@sofaking/core';
import { h, replaceChildren } from './dom';
import { keepFocus } from './focus';
import { card } from './libraryView';
import { href } from './router';
import { airingWhen, rowTitle, SEARCH_DEBOUNCE_MS, SEARCH_HINT, searchStatus } from './search';
import type { Context, View } from './view';

/** A channel to watch now: its logo and number, and the program it was found by, or its name over what is on. */
function nowRow(hit: WebLiveHit): HTMLElement {
    const { channel, program } = hit;
    return h(
        'a',
        { class: 'channel hit', attrs: { href: href({ page: 'watch', id: channel.id }), 'data-key': `now:${channel.id}` } },
        channel.logo ? h('img', { class: 'logo', attrs: { src: channel.logo, alt: '', loading: 'lazy' } }) : h('span', { class: 'logo' }),
        h('span', { class: 'number', text: String(channel.number) }),
        h(
            'span',
            { class: 'what' },
            h('span', { class: 'now', text: program?.title ?? channel.name }),
            h('span', { class: 'next', text: program ? channel.name : 'Nothing listed' }),
        ),
    );
}

/** A program coming up: when it airs and where. The page has no recordings, so it is a line to read, not a link. */
function laterRow(hit: WebLiveHit, at: number): HTMLElement {
    const { channel, program } = hit;
    return h(
        'div',
        { class: 'channel hit later' },
        channel.logo ? h('img', { class: 'logo', attrs: { src: channel.logo, alt: '', loading: 'lazy' } }) : h('span', { class: 'logo' }),
        h('span', { class: 'number', text: String(channel.number) }),
        h(
            'span',
            { class: 'what' },
            h('span', { class: 'now', text: program?.title ?? channel.name }),
            h('span', { class: 'next', text: program ? `${airingWhen(program.startMs, at)} · ${channel.name}` : channel.name }),
        ),
    );
}

/**
 * One search for everything: channels on now, the libraries' titles and cast, and programs coming up. The query is in
 * the page's address, so Back from a result returns to its results.
 */
export function searchView(ctx: Context, initial: string): View {
    const field = h('input', {
        class: 'search wide',
        attrs: { type: 'search', placeholder: SEARCH_HINT, 'aria-label': 'Search', autocomplete: 'off', enterkeyhint: 'search' },
    });
    field.value = initial;
    const status = h('p', { class: 'status', attrs: { role: 'status' } });
    const results = h('div', { class: 'results' });
    const el = h('section', { class: 'search-page' }, h('div', { class: 'bar' }, field), status, results);

    let found: WebSearchResults | null = null;
    let searching = false;
    // The search asked for last; an answer to an earlier one is dropped.
    let asked = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let gone = false;

    const draw = () => {
        const query = field.value;
        status.textContent = searchStatus(query, searching, found);
        status.hidden = status.textContent === '';
        keepFocus(results, () => {
            if (!found || !query.trim()) {
                replaceChildren(results);
                return;
            }
            const at = Date.now();
            const shown = found;
            replaceChildren(
                results,
                shown.now.length > 0 ? h('div', { class: 'shelf' }, h('h2', { text: 'On now' }), h('div', { class: 'channels' }, ...shown.now.map(nowRow))) : null,
                ...shown.rows.map((row) =>
                    h(
                        'div',
                        { class: 'shelf' },
                        h('h2', { text: rowTitle(row.group, shown.people, shown.query) }),
                        h('div', { class: 'cards' }, ...row.items.map((item) => card(item, row.group))),
                    ),
                ),
                shown.later.length > 0
                    ? h('div', { class: 'shelf' }, h('h2', { text: 'On later' }), h('div', { class: 'channels' }, ...shown.later.map((hit) => laterRow(hit, at))))
                    : null,
            );
        });
    };

    const run = () => {
        const query = field.value.trim();
        ctx.note({ page: 'search', query });
        const mine = ++asked;
        if (!query) {
            found = null;
            searching = false;
            draw();
            return;
        }
        searching = true;
        draw();
        void ctx.core.search.find(query).then(
            (answer) => {
                if (gone || mine !== asked) return;
                found = answer;
                searching = false;
                draw();
            },
            () => {
                if (gone || mine !== asked) return;
                found = null;
                searching = false;
                status.textContent = 'The search did not finish. Try again.';
                status.hidden = false;
            },
        );
    };

    field.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(run, SEARCH_DEBOUNCE_MS);
    });
    // Enter searches now, without waiting out the pause.
    field.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter') return;
        clearTimeout(timer);
        run();
    });
    // The lineup may still be loading as the page opens on a query; its hits follow once it is in.
    let ready = false;
    const lineup = ctx.core.guide.watchLineup((next) => {
        const now = next.state === 'ready';
        if (now && !ready && field.value.trim()) run();
        ready = now;
    });
    if (initial.trim()) run();
    else draw();

    return {
        el,
        focus: () => field.focus(),
        dispose() {
            gone = true;
            clearTimeout(timer);
            lineup.cancel();
        },
    };
}
