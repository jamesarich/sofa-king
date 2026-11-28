import type { WebChannel, WebLineup, WebProgram, WebSettingsState } from '@sofaking/core';
import { h, replaceChildren } from './dom';
import { focusFirst, focusables, keepFocus } from './focus';
import { chipsFor, clock, elapsed, filterChannels, nowNext, type GuidePrefs } from './guide';
import { guideGrid, programDetails } from './guideGrid';
import { guideStatus, isSignedOut } from './messages';
import { href } from './router';
import { signedOutNotice } from './signedOut';
import { Watches, type Context, type View } from './view';

const NO_PREFS: WebSettingsState | null = null;

const VIEW_KEY = 'sofaking.web.guideView';
/** Narrower than this, a window opens on the list: a grid row shows little beside its channel. */
const GRID_MIN_WIDTH = 700;

type GuideLayout = 'grid' | 'list';

/** The layout chosen last in this browser, or the one that fits the window. */
function savedLayout(): GuideLayout {
    let saved: string | null = null;
    try {
        saved = window.localStorage.getItem(VIEW_KEY);
    } catch {
        // A browser that keeps no storage opens on the default.
    }
    if (saved === 'grid' || saved === 'list') return saved;
    return window.innerWidth >= GRID_MIN_WIDTH ? 'grid' : 'list';
}

/**
 * The guide: the channels as a grid of programs on a timeline or a list of what is on now and next, under chips that
 * filter them.
 */
export function guideView(ctx: Context): View {
    const watches = new Watches();
    const el = h('section', { class: 'guide' });
    const chips = h('div', { class: 'chips', attrs: { role: 'group', 'aria-label': 'Filter channels' } });
    const search = h('input', {
        class: 'search',
        attrs: { type: 'search', placeholder: 'Search channels and programs', 'aria-label': 'Search' },
    });
    const list = h('div', { class: 'channels' });
    const details = h('div', { class: 'details-slot' });
    let layout = savedLayout();
    const layoutButton = (value: GuideLayout, label: string) =>
        h('button', {
            class: 'chip',
            text: label,
            attrs: { type: 'button', 'data-key': `layout:${value}` },
            on: {
                click: () => {
                    layout = value;
                    try {
                        window.localStorage.setItem(VIEW_KEY, value);
                    } catch {
                        // The choice lasts for this page.
                    }
                    draw();
                },
            },
        });
    const layouts: Array<[GuideLayout, HTMLButtonElement]> = [
        ['grid', layoutButton('grid', 'Grid')],
        ['list', layoutButton('list', 'List')],
    ];
    const layoutGroup = h('div', { class: 'chips', attrs: { role: 'group', 'aria-label': 'Guide layout' } }, ...layouts.map(([, element]) => element));
    const status = h('p', { class: 'status', attrs: { role: 'status' } });
    const signedOut = signedOutNotice(true);
    signedOut.hidden = true;
    el.append(h('div', { class: 'bar' }, chips, search, layoutGroup), status, signedOut, details, list);

    let lineup: WebLineup | null = null;
    let prefs = NO_PREFS;
    let focused = false;
    // The later program whose details are open, and the cell to give focus back to as they close.
    let open: { channel: WebChannel; program: WebProgram; key: string } | null = null;

    const closeDetails = () => {
        const key = open?.key;
        open = null;
        draw();
        if (key) focusables(list).find((element) => element.dataset.key === key)?.focus();
    };
    const openDetails = (channel: WebChannel, program: WebProgram) => {
        open = { channel, program, key: `prog:${channel.id}:${program.startMs}` };
        draw();
        focusFirst(details);
    };

    const draw = () => {
        keepFocus(el, () => {
            const settings: GuidePrefs & { guideFilter?: string | null } = prefs ?? {
                favorites: [],
                recentChannels: [],
            };
            const channels = lineup?.state === 'ready' ? [...lineup.channels] : [];
            const key = settings.guideFilter ?? null;
            const known = chipsFor(channels, settings);
            // A filter whose channels are gone shows All.
            const active = known.some((chip) => chip.key === key) ? key : null;
            replaceChildren(
                chips,
                ...known.map((chip) =>
                    h('button', {
                        class: chip.key === active ? 'chip on' : 'chip',
                        text: chip.label,
                        attrs: { type: 'button', 'aria-pressed': chip.key === active, 'data-key': `chip:${chip.key}` },
                        on: { click: () => ctx.core.guide.setFilter(chip.key) },
                    }),
                ),
            );
            const shown = filterChannels(channels, active, settings, search.value);
            for (const [value, element] of layouts) {
                element.classList.toggle('on', value === layout);
                element.setAttribute('aria-pressed', String(value === layout));
            }
            list.className = layout === 'grid' ? 'guide-grid' : 'channels';
            if (layout === 'grid') {
                const grid = guideGrid(shown, settings.favorites, Date.now(), {
                    toggleFavorite: (id) => ctx.core.guide.toggleFavorite(id),
                    details: openDetails,
                });
                // The grid scrolls sideways inside itself; a redraw keeps the time it was scrolled to.
                const scrolled = list.firstElementChild?.scrollLeft ?? 0;
                replaceChildren(list, shown.length > 0 ? grid : null);
                grid.scrollLeft = scrolled;
            } else {
                replaceChildren(list, ...shown.map((channel) => row(ctx, channel, settings.favorites.includes(channel.id))));
            }
            // A program's details close when its channel leaves the lineup or the layout changes to the list.
            if (open && (layout !== 'grid' || !channels.some((channel) => channel.id === open?.channel.id))) open = null;
            replaceChildren(details, open ? programDetails(open.channel, open.program, closeDetails) : null);
            const out = isSignedOut(lineup);
            signedOut.hidden = !out;
            status.textContent = out ? '' : guideStatus(lineup, shown.length, search.value);
            status.hidden = status.textContent === '';
        });
        if (!focused && isSignedOut(lineup)) {
            focused = true;
            focusFirst(signedOut);
        }
        if (!focused && lineup?.state === 'ready' && list.firstElementChild) {
            focused = true;
            focusFirst(list);
        }
    };

    search.addEventListener('input', draw);
    watches.add(ctx.core.guide.watchLineup((next) => {
        lineup = next;
        draw();
    }));
    watches.add(ctx.core.guide.watchSettings((next) => {
        prefs = next;
        draw();
    }));
    // What is on now moves with the clock.
    const tick = setInterval(draw, 60_000);

    return {
        el,
        dispose() {
            clearInterval(tick);
            watches.cancel();
        },
    };
}

function row(ctx: Context, channel: WebChannel, favorite: boolean): HTMLElement {
    const at = Date.now();
    const { now, next } = nowNext(channel, at);
    const link = h(
        'a',
        { class: 'channel', attrs: { href: href({ page: 'watch', id: channel.id }), 'data-key': `ch:${channel.id}` } },
        channel.logo ? h('img', { class: 'logo', attrs: { src: channel.logo, alt: '', loading: 'lazy' } }) : h('span', { class: 'logo' }),
        h('span', { class: 'number', text: String(channel.number) }),
        h(
            'span',
            { class: 'what' },
            h('span', { class: 'name', text: channel.name }),
            h('span', { class: 'now', text: now ? now.title : 'Nothing listed' }),
            now ? h('span', { class: 'meter' }, h('span', { class: 'fill', style: { width: `${Math.round(elapsed(now, at) * 100)}%` } })) : null,
            next ? h('span', { class: 'next', text: `${clock(next.startMs)} ${next.title}` }) : null,
        ),
    );
    const star = h('button', {
        class: favorite ? 'star on' : 'star',
        text: favorite ? '★' : '☆',
        attrs: {
            type: 'button',
            'aria-label': `${favorite ? 'Remove' : 'Add'} ${channel.name} ${favorite ? 'from' : 'to'} favorites`,
            'aria-pressed': favorite,
            'data-key': `star:${channel.id}`,
        },
        on: { click: () => ctx.core.guide.toggleFavorite(channel.id) },
    });
    return h('div', { class: 'row' }, link, star);
}
