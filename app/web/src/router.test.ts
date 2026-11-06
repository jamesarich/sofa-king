import assert from 'node:assert/strict';
import { test } from 'node:test';
import { backStaysInApp, href, parseRoute, stampFor, type Route } from './router';

test('every route reads back from the address it makes', () => {
    const routes: Route[] = [
        { page: 'guide' },
        { page: 'watch', id: 'ch 1/x' },
        { page: 'library' },
        { page: 'grid', id: 'lib1' },
        { page: 'item', id: 'i/2' },
        { page: 'play', id: 'i3', fromStart: true },
        { page: 'play', id: 'i3', fromStart: false },
        { page: 'settings' },
    ];
    for (const route of routes) assert.deepEqual(parseRoute(href(route)), route);
});

test('an empty or unknown address opens the guide, and a page without its id the page above it', () => {
    assert.deepEqual(parseRoute(''), { page: 'guide' });
    assert.deepEqual(parseRoute('#/nowhere'), { page: 'guide' });
    assert.deepEqual(parseRoute('#/watch'), { page: 'guide' });
    assert.deepEqual(parseRoute('#/item'), { page: 'library' });
});

test('Back goes up the history only from an entry the app stamped above its first', () => {
    assert.equal(backStaysInApp(null), false);
    assert.equal(backStaysInApp({ sk: 0 }), false);
    assert.equal(backStaysInApp({ sk: 2 }), true);
    assert.equal(backStaysInApp({ sk: -1 }), false);
    assert.equal(backStaysInApp({ other: 1 }), false);
});

test('a new entry is stamped one deeper, and a revisited one keeps its stamp', () => {
    assert.deepEqual(stampFor(null, 0), { sk: 1 });
    assert.deepEqual(stampFor({ sk: 3 }, 5), { sk: 3 });
});
