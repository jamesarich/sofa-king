import assert from 'node:assert/strict';
import { test } from 'node:test';
import { extraLabel, initials, length, position } from './format';

test('a length reads in hours and minutes', () => {
    assert.equal(length(45 * 60_000), '45 min');
    assert.equal(length(2 * 3_600_000), '2 h');
    assert.equal(length(112 * 60_000), '1 h 52 min');
    assert.equal(length(null), '');
    assert.equal(length(0), '');
});

test('a position reads as the player shows it', () => {
    assert.equal(position(65), '1:05');
    assert.equal(position(3723), '1:02:03');
    assert.equal(position(-4), '0:00');
});

test("a name's initials are its first and last words' first letters", () => {
    assert.equal(initials('Ana Reyes'), 'AR');
    assert.equal(initials('Mary Jo  van Dyke'), 'MD');
    assert.equal(initials('cher'), 'C');
    assert.equal(initials(''), '');
});

test('an extra is named by its kind, and one the page does not know is an Extra', () => {
    assert.equal(extraLabel('trailer'), 'Trailer');
    assert.equal(extraLabel('behind_the_scenes'), 'Behind the scenes');
    assert.equal(extraLabel('other'), 'Extra');
    assert.equal(extraLabel(null), 'Extra');
});
