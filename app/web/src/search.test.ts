import assert from 'node:assert/strict';
import { test } from 'node:test';
import { airingWhen, peopleTitle, rowTitle, searchStatus } from './search';

test('the cast row names one person, two, or the first two of more', () => {
    assert.equal(peopleTitle(['Ada Lovelace'], 'lovelace'), 'With Ada Lovelace');
    assert.equal(peopleTitle(['Ada Lovelace', 'Byron Lovelace'], 'lovelace'), 'With Ada Lovelace and Byron Lovelace');
    assert.equal(peopleTitle(['Ada Lovelace', 'Byron Lovelace', 'Kit Lovelace'], 'lovelace'), 'With Ada Lovelace, Byron Lovelace and others');
});

test('a server that names no one titles the cast row by the query', () => {
    assert.equal(peopleTitle([], 'lovelace'), 'With lovelace');
});

test('a library row is titled by its group', () => {
    assert.equal(rowTitle('movies', [], 'x'), 'Movies');
    assert.equal(rowTitle('tracks', [], 'x'), 'Songs');
    assert.equal(rowTitle('cast_and_crew', ['Ada Lovelace'], 'x'), 'With Ada Lovelace');
});

test('a program airs today, tomorrow or on a named day', () => {
    const now = new Date(2026, 10, 27, 18, 0).getTime();
    assert.equal(airingWhen(new Date(2026, 10, 27, 20, 0).getTime(), now, 'en-US'), 'Today 8:00 PM');
    assert.equal(airingWhen(new Date(2026, 10, 28, 9, 30).getTime(), now, 'en-US'), 'Tomorrow 9:30 AM');
    assert.equal(airingWhen(new Date(2026, 10, 29, 19, 0).getTime(), now, 'en-US'), 'Sun 7:00 PM');
});

test('tomorrow is the next date across the end of a month', () => {
    const now = new Date(2026, 10, 30, 23, 0).getTime();
    assert.equal(airingWhen(new Date(2026, 11, 1, 1, 0).getTime(), now, 'en-US'), 'Tomorrow 1:00 AM');
});

test('the page says what it searches, that it is searching, and what found nothing', () => {
    const none = { now: [], rows: [], later: [] };
    assert.match(searchStatus('  ', false, null), /^Search the channels/);
    assert.equal(searchStatus('news', true, none), 'Searching');
    assert.equal(searchStatus('news', false, null), 'Searching');
    assert.equal(searchStatus(' zebra ', false, none), 'Nothing matches "zebra".');
    assert.equal(searchStatus('news', false, { ...none, later: [1] }), '');
});
