import assert from 'node:assert/strict';
import { test } from 'node:test';
import { chipsFor, elapsed, filterChannels, nowNext, type ChannelLike } from './guide';

const MIN = 60_000;
const NOW = 1_000 * MIN;

function channel(id: string, number: number, name: string, groups: string[], programs: ChannelLike['programs'] = []) {
    return { id, number, name, groups, programs };
}

const news = channel('n', 4, 'News 4', ['News', 'Local'], [
    { title: 'Evening news', startMs: NOW - 10 * MIN, stopMs: NOW + 20 * MIN },
    { title: 'Weather', startMs: NOW + 20 * MIN, stopMs: NOW + 30 * MIN },
]);
const movies = channel('m', 2, 'Movie Time', ['Movies'], [{ title: 'Heat', startMs: NOW - 60 * MIN, stopMs: NOW + 60 * MIN }]);
const sports = channel('s', 9, 'Sports', ['Sports', 'Local']);
const all = [news, movies, sports];

test('the chips are All, then Favorites and Recent when set, then each group by name', () => {
    const labels = (favorites: string[], recent: string[]) =>
        chipsFor(all, { favorites, recentChannels: recent }).map((chip) => chip.label);
    assert.deepEqual(labels([], []), ['All', 'Local', 'Movies', 'News', 'Sports']);
    assert.deepEqual(labels(['m'], ['s']), ['All', 'Favorites', 'Recent', 'Local', 'Movies', 'News', 'Sports']);
    // A favorite the lineup no longer has does not make a chip.
    assert.deepEqual(labels(['gone'], []), ['All', 'Local', 'Movies', 'News', 'Sports']);
});

test('a group chip keeps the channels filed under it, by number', () => {
    const kept = filterChannels(all, 'group:Local', { favorites: [], recentChannels: [] });
    assert.deepEqual(kept.map((c) => c.id), ['n', 's']);
});

test('All lists every channel by number', () => {
    assert.deepEqual(filterChannels(all, null, { favorites: [], recentChannels: [] }).map((c) => c.id), ['m', 'n', 's']);
});

test('Favorites keeps the starred channels and Recent keeps the order they were watched in', () => {
    const prefs = { favorites: ['s', 'm'], recentChannels: ['s', 'n'] };
    assert.deepEqual(filterChannels(all, 'favorites', prefs).map((c) => c.id), ['m', 's']);
    assert.deepEqual(filterChannels(all, 'recent', prefs).map((c) => c.id), ['s', 'n']);
});

test('a search matches a channel by name or number, or by a program on it', () => {
    const none = { favorites: [], recentChannels: [] };
    assert.deepEqual(filterChannels(all, null, none, 'movie').map((c) => c.id), ['m']);
    assert.deepEqual(filterChannels(all, null, none, '9').map((c) => c.id), ['s']);
    assert.deepEqual(filterChannels(all, null, none, 'weather').map((c) => c.id), ['n']);
    assert.deepEqual(filterChannels(all, 'group:Local', none, 'sport').map((c) => c.id), ['s']);
});

test('now is the program covering the time and next the one after it', () => {
    const { now, next } = nowNext(news, NOW);
    assert.equal(now?.title, 'Evening news');
    assert.equal(next?.title, 'Weather');
});

test('between programs there is no now, and next is the first to start', () => {
    const gap = channel('g', 1, 'Gap', [], [{ title: 'Later', startMs: NOW + 5 * MIN, stopMs: NOW + 9 * MIN }]);
    const { now, next } = nowNext(gap, NOW);
    assert.equal(now, null);
    assert.equal(next?.title, 'Later');
    assert.deepEqual(nowNext(sports, NOW), { now: null, next: null });
});

test('how far through a program it is stays between 0 and 1', () => {
    const program = { title: 'x', startMs: 0, stopMs: 100 };
    assert.equal(elapsed(program, 25), 0.25);
    assert.equal(elapsed(program, -5), 0);
    assert.equal(elapsed(program, 500), 1);
});
