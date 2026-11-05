import assert from 'node:assert/strict';
import { test } from 'node:test';
import { href, parseRoute, type Route } from './router';

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
