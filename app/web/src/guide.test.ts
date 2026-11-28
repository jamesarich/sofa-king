import assert from 'node:assert/strict';
import { test } from 'node:test';
import { chipBar, chipsFor, elapsed, filterChannels, gridCells, gridPx, gridSlots, gridStart, nowNext, programWhen, type ChannelLike } from './guide';

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

test('a few groups are a chip each and many go in one menu beside All, Favorites and Recent', () => {
    const chips = chipsFor(all, { favorites: ['m'], recentChannels: [] });
    assert.deepEqual(chipBar(chips), { chips, menu: [] });
    const split = chipBar(chips, 3);
    assert.deepEqual(split.chips.map((chip) => chip.label), ['All', 'Favorites']);
    assert.deepEqual(split.menu.map((chip) => chip.key), ['group:Local', 'group:Movies', 'group:News', 'group:Sports']);
    assert.deepEqual(chipBar(chips, 4).menu, [], 'as many groups as the bar holds stay chips');
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

test('the grid starts at the half hour now is in', () => {
    const at = (h: number, m: number) => new Date(2026, 10, 27, h, m, 12).getTime();
    assert.equal(gridStart(at(20, 10)), new Date(2026, 10, 27, 20, 0).getTime());
    assert.equal(gridStart(at(20, 30)), new Date(2026, 10, 27, 20, 30).getTime());
    assert.equal(gridStart(at(20, 59)), new Date(2026, 10, 27, 20, 30).getTime());
});

test('the row of times has a mark each half hour for six hours', () => {
    const slots = gridSlots(NOW);
    assert.equal(slots.length, 12);
    assert.equal(slots[0], NOW);
    assert.equal(slots[11], NOW + 330 * MIN);
    assert.equal(gridPx(30 * MIN), 180);
});

test('a row cuts its programs to the window and fills what the guide does not list', () => {
    const programs = [
        { title: 'Late', startMs: NOW + 50 * MIN, stopMs: NOW + 90 * MIN },
        { title: 'On now', startMs: NOW - 20 * MIN, stopMs: NOW + 10 * MIN },
    ];
    const cells = gridCells(programs, NOW, NOW + 60 * MIN);
    assert.deepEqual(cells.map((cell) => [cell.program?.title ?? null, (cell.startMs - NOW) / MIN, (cell.stopMs - NOW) / MIN]), [
        ['On now', 0, 10],
        [null, 10, 50],
        ['Late', 50, 60],
    ]);
});

test('a row with no programs is one gap, and overlapping programs do not overlap in it', () => {
    assert.deepEqual(gridCells([], NOW, NOW + 60 * MIN), [{ program: null, startMs: NOW, stopMs: NOW + 60 * MIN }]);
    const overlapping = [
        { title: 'A', startMs: NOW, stopMs: NOW + 40 * MIN },
        { title: 'B', startMs: NOW + 30 * MIN, stopMs: NOW + 60 * MIN },
        { title: 'Inside A', startMs: NOW + 5 * MIN, stopMs: NOW + 20 * MIN },
    ];
    const cells = gridCells(overlapping, NOW, NOW + 60 * MIN);
    assert.deepEqual(cells.map((cell) => [cell.program?.title, (cell.startMs - NOW) / MIN, (cell.stopMs - NOW) / MIN]), [
        ['A', 0, 40],
        ['B', 40, 60],
    ]);
});

test('a program in its details says its hours and its channel', () => {
    const program = { title: 'Heat', startMs: new Date(2026, 10, 27, 20, 0).getTime(), stopMs: new Date(2026, 10, 27, 21, 30).getTime() };
    assert.equal(programWhen(program, { number: 5, name: 'News 5' }, 'en-US'), '8:00 PM to 9:30 PM · 5 News 5');
});
