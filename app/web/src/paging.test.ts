import assert from 'node:assert/strict';
import { test } from 'node:test';
import { extendsShown, loadsItself, moreLabel, nearEnd, NEAR_END_PX } from './paging';

const more = { hasMore: true, loading: false, error: null };

test('the end is near within the margin below the window', () => {
    assert.equal(nearEnd(900, 900), true);
    assert.equal(nearEnd(900 + NEAR_END_PX, 900), true);
    assert.equal(nearEnd(901 + NEAR_END_PX, 900), false);
});

test('the next page loads by itself near the end', () => {
    assert.equal(loadsItself(more, true), true);
    assert.equal(loadsItself(more, false), false);
});

test('no page loads while one is on its way, after the last, or after one failed', () => {
    assert.equal(loadsItself({ ...more, loading: true }, true), false);
    assert.equal(loadsItself({ ...more, hasMore: false }, true), false);
    assert.equal(loadsItself({ ...more, error: 'HTTP 500' }, true), false);
});

test('the button stays away while pages load by themselves', () => {
    assert.equal(moreLabel(more, true), null);
    assert.equal(moreLabel({ ...more, hasMore: false }, false), null);
    assert.equal(moreLabel({ ...more, loading: true }, false), null);
});

test('the button tries a failed page again, and loads more where the end cannot be watched', () => {
    assert.equal(moreLabel({ ...more, error: 'HTTP 500' }, true), 'Try again');
    assert.equal(moreLabel(more, false), 'Load more');
});

test('a page that follows the cards shown only adds its own', () => {
    assert.equal(extendsShown(['a', 'b'], ['a', 'b', 'c']), true);
    assert.equal(extendsShown(['a', 'b'], ['a', 'b']), true);
});

test('a grid that changed above its end, shrank or was empty is drawn again', () => {
    assert.equal(extendsShown(['a', 'b'], ['a', 'x', 'c']), false);
    assert.equal(extendsShown(['a', 'b'], ['a']), false);
    assert.equal(extendsShown([], ['a']), false);
});
