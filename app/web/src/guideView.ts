import type { WebChannel, WebLineup, WebSettingsState } from '@sofaking/core';
import { h, replaceChildren } from './dom';
import { focusFirst, keepFocus } from './focus';
import { chipsFor, clock, elapsed, filterChannels, nowNext, type GuidePrefs } from './guide';
import { guideStatus, isSignedOut } from './messages';
import { href } from './router';
import { signedOutNotice } from './signedOut';
import { Watches, type Context, type View } from './view';

const NO_PREFS: WebSettingsState | null = null;

/** The guide: a channel list with what is on now and next, and chips that filter it. */
export function guideView(ctx: Context): View {
    const watches = new Watches();
    const el = h('section', { class: 'guide' });
    const chips = h('div', { class: 'chips', attrs: { role: 'group', 'aria-label': 'Filter channels' } });
    const search = h('input', {
        class: 'search',
        attrs: { type: 'search', placeholder: 'Search channels and programs', 'aria-label': 'Search' },
    });
    const list = h('div', { class: 'channels' });
    const status = h('p', { class: 'status', attrs: { role: 'status' } });
    const signedOut = signedOutNotice(true);
    signedOut.hidden = true;
    el.append(h('div', { class: 'bar' }, chips, search), status, signedOut, list);

    let lineup: WebLineup | null = null;
    let prefs = NO_PREFS;
    let focused = false;

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
            replaceChildren(list, ...shown.map((channel) => row(ctx, channel, settings.favorites.includes(channel.id))));
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
