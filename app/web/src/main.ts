import { SofaKingWeb } from '@sofaking/core';
import './style.css';
import { h, replaceChildren } from './dom';
import { focusables, focusFirst, installKeys } from './focus';
import { guideView } from './guideView';
import { itemView } from './itemView';
import { gridView, libraryView } from './libraryView';
import { liveView } from './liveView';
import { pairingMessage, takePairing } from './pairing';
import { backStaysInApp, href, parseRoute, stampFor, type Route } from './router';
import { noteFromPairing, settingsView } from './settingsView';
import type { Context, View } from './view';
import { vodView } from './vodView';

const core = new SofaKingWeb();
const root = document.getElementById('app') as HTMLElement;

// Every history entry the app is on carries a stamp, so Back never leaves the app's own pages: the first entry, or
// one the app did not make, goes to the guide in place.
let stamp = stampFor(window.history.state, -1);
window.history.replaceState(stamp, '');

const ctx: Context = {
    core,
    go: (route) => {
        window.location.hash = href(route);
    },
    replace: (route) => {
        window.history.replaceState(window.history.state, '', href(route));
        show();
    },
    back: () => {
        if (backStaysInApp(window.history.state)) {
            window.history.back();
        } else {
            window.location.hash = href({ page: 'guide' });
        }
    },
};

const nav = h('nav', { class: 'nav', attrs: { 'aria-label': 'Sections' } });
const main = h('main', { class: 'page' });
replaceChildren(root, nav, main);

let view: View | null = null;
let libraryOffered = false;

function section(route: Route): string {
    switch (route.page) {
        case 'guide':
        case 'watch':
            return 'guide';
        case 'settings':
            return 'settings';
        default:
            return 'library';
    }
}

function drawNav(route: Route): void {
    const here = section(route);
    const links: Array<[string, string, string]> = [['guide', 'Guide', href({ page: 'guide' })]];
    if (libraryOffered) links.push(['library', 'Library', href({ page: 'library' })]);
    links.push(['settings', 'Settings', href({ page: 'settings' })]);
    replaceChildren(
        nav,
        h('span', { class: 'brand', text: 'Sofa King' }),
        ...links.map(([id, label, target]) =>
            h('a', { text: label, class: id === here ? 'on' : '', attrs: { href: target, 'aria-current': id === here ? 'page' : undefined } }),
        ),
    );
}

function create(route: Route): View {
    switch (route.page) {
        case 'guide':
            return guideView(ctx);
        case 'watch':
            return liveView(ctx, route.id);
        case 'library':
            return libraryView(ctx);
        case 'grid':
            return gridView(ctx, route.id);
        case 'item':
            return itemView(ctx, route.id);
        case 'play':
            return vodView(ctx, route.id, route.fromStart);
        case 'settings':
            return settingsView(ctx);
    }
}

function show(): void {
    const route = parseRoute(window.location.hash);
    view?.dispose();
    drawNav(route);
    view = create(route);
    document.body.classList.toggle('playing', route.page === 'watch' || route.page === 'play');
    replaceChildren(main, view.el);
    // Keys go to the focused control; a page that does not focus one itself gets its first.
    requestAnimationFrame(() => {
        const active = document.activeElement;
        if (!active || active === document.body || !view?.el.contains(active)) {
            if (view?.focus) view.focus();
            else focusFirst(view?.el ?? main);
        }
        if (document.activeElement === document.body) focusables(root)[0]?.focus();
    });
}

window.addEventListener('hashchange', () => {
    stamp = stampFor(window.history.state, stamp.sk);
    window.history.replaceState(stamp, '');
    show();
});

installKeys(root, ctx.back);

core.channels.watchCapabilities((capabilities) => {
    if (capabilities.library === libraryOffered) return;
    libraryOffered = capabilities.library;
    drawNav(parseRoute(window.location.hash));
});

// A code in the address pairs this page with the server that serves it, and opens the guide.
const pairing = takePairing(core.text, window.location, window.history);
if (pairing) window.history.replaceState(stamp, '');
show();
if (pairing) {
    void core.settings.pair(pairing.serverUrl, pairing.code).then((failure) => {
        if (failure) noteFromPairing(pairingMessage(failure));
        window.location.hash = href({ page: failure ? 'settings' : 'guide' });
    });
}

if ('serviceWorker' in navigator) void navigator.serviceWorker.register('sw.js');
