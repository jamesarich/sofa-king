import assert from 'node:assert/strict';
import { test } from 'node:test';
import { audioRows, cueTimes, drawnIn, offersTracks, subtitleRows, toggledSubtitle, type Tracks } from './tracks';

const english = { index: 1, label: 'English · AC3 5.1', picture: false };
const french = { index: 2, label: 'French · AAC Stereo', picture: false };
const text = { index: 3, label: 'English', picture: false };
const pgs = { index: 4, label: 'French', picture: true };
const file: Tracks = { audio: [english, french], subtitles: [text, pgs] };

test('a picker is offered for a second audio track or any subtitles', () => {
    assert.equal(offersTracks(null), false);
    assert.equal(offersTracks({ audio: [english], subtitles: [] }), false);
    assert.equal(offersTracks({ audio: [english, french], subtitles: [] }), true);
    assert.equal(offersTracks({ audio: [english], subtitles: [text] }), true);
});

test('audio lists every track with the playing one on, and nothing for a single track', () => {
    assert.deepEqual(audioRows(file, 2), [
        { index: 1, label: 'English · AC3 5.1', on: false },
        { index: 2, label: 'French · AAC Stereo', on: true },
    ]);
    assert.deepEqual(audioRows({ audio: [english], subtitles: [text] }, 1), []);
});

test('subtitles start with Off, which is on when none shows', () => {
    assert.deepEqual(
        subtitleRows(file, null).map((row) => [row.label, row.on]),
        [['Off', true], ['English', false], ['French', false]],
    );
    assert.deepEqual(
        subtitleRows(file, 4).map((row) => row.on),
        [false, false, true],
    );
    assert.equal(subtitleRows(file, 99)[0]?.on, true, 'a track the file does not have shows as Off');
    assert.deepEqual(subtitleRows({ audio: [english], subtitles: [] }, null), []);
});

test('only a picture subtitle is asked of the stream', () => {
    assert.equal(drawnIn(file, 4), 4);
    assert.equal(drawnIn(file, 3), null);
    assert.equal(drawnIn(file, null), null);
    assert.equal(drawnIn(null, 4), null);
});

test('C turns subtitles off, then back on with the ones shown last or the first', () => {
    assert.equal(toggledSubtitle(file, 3, 3), null);
    assert.equal(toggledSubtitle(file, null, 4), 4);
    assert.equal(toggledSubtitle(file, null, null), 3);
    assert.equal(toggledSubtitle(file, null, 99), 3, 'a track from another file falls back to the first');
    assert.equal(toggledSubtitle({ audio: [english], subtitles: [] }, null, null), null);
});

test('cues move to the stream\'s own clock and the ones already over are dropped', () => {
    const cues = [
        { startMs: 1_000, endMs: 3_000, text: 'over' },
        { startMs: 9_000, endMs: 12_000, text: 'under way' },
        { startMs: 15_000, endMs: 17_500, text: 'ahead' },
    ];
    assert.deepEqual(cueTimes(cues, 0), [
        { start: 1, end: 3, text: 'over' },
        { start: 9, end: 12, text: 'under way' },
        { start: 15, end: 17.5, text: 'ahead' },
    ]);
    assert.deepEqual(cueTimes(cues, 10_000), [
        { start: 0, end: 2, text: 'under way' },
        { start: 5, end: 7.5, text: 'ahead' },
    ]);
});
