import assert from 'node:assert/strict';
import { test } from 'node:test';
import { showsLetters, titleCount, type Letter } from './letters';

const letters: Letter[] = 'ABCDEFG'.split('').map((letter, index) => ({ letter, offset: index * 20 }));

test('the row shows for a long grid in title order with enough letters', () => {
    assert.equal(showsLetters('title', 140, letters), true);
});

test('the row stays away in another order, in a short grid or with few letters', () => {
    assert.equal(showsLetters('added', 140, letters), false);
    assert.equal(showsLetters('title', 79, letters), false);
    assert.equal(showsLetters('title', 140, letters.slice(0, 5)), false);
});

test('the count says title once and titles otherwise', () => {
    assert.equal(titleCount(1), '1 title');
    assert.equal(titleCount(0), '0 titles');
    assert.equal(titleCount(1240), '1,240 titles');
});
