import assert from 'node:assert/strict';
import { test } from 'node:test';
import { WebBreakMarks } from '@sofaking/core';
import { BreakTracker, countdown, dateRangeLine, NO_BREAK, type DateRangeLike } from './breaks';

// The ad range the server writes beside each ad tag, as hls.js reads it: attribute values without their quotes.
const START = Date.parse('2026-11-04T12:00:00.000Z');
const AD_CLASS = 'dev.jamesrich.sofaking.ad';

function range(id: string, extra: Record<string, string> = {}): DateRangeLike {
    return { attr: { ID: id, CLASS: AD_CLASS, 'START-DATE': '2026-11-04T12:00:00.000Z', ...extra } };
}

test('a date range becomes the playlist line core reads', () => {
    assert.equal(
        dateRangeLine(range('ad1', { DURATION: '90.5' })),
        `#EXT-X-DATERANGE:ID="ad1",CLASS="${AD_CLASS}",START-DATE="2026-11-04T12:00:00.000Z",DURATION=90.5`,
    );
});

test('a range core knows as an ad marks the picture inside it, with the time it has left', () => {
    const tracker = new BreakTracker(new WebBreakMarks());
    tracker.setMode('SCREEN');
    tracker.onRanges({ ad1: range('ad1', { DURATION: '90' }) });

    assert.deepEqual(tracker.update(START - 1), NO_BREAK);
    assert.deepEqual(tracker.update(START + 30_000), {
        covered: true,
        muted: false,
        remainingMs: 60_000,
        pictureShown: false,
    });
    assert.deepEqual(tracker.update(START + 90_000), NO_BREAK);
});

test('a range of another class is no break', () => {
    const tracker = new BreakTracker(new WebBreakMarks());
    tracker.setMode('SCREEN');
    tracker.onRanges({ x: { attr: { ID: 'x', CLASS: 'other', 'START-DATE': '2026-11-04T12:00:00.000Z', DURATION: '90' } } });
    assert.deepEqual(tracker.update(START + 1_000), NO_BREAK);
});

test('a later playlist that learns the length updates the break in place', () => {
    const tracker = new BreakTracker(new WebBreakMarks());
    tracker.setMode('SCREEN');
    tracker.onRanges({ ad1: range('ad1') });
    assert.equal(tracker.update(START + 10_000).remainingMs, null);
    assert.equal(tracker.update(START + 10_000).covered, true);

    tracker.onRanges({ ad1: range('ad1', { DURATION: '40' }) });
    assert.equal(tracker.update(START + 10_000).remainingMs, 30_000);
});

test('a range with no end that a later playlist no longer lists stops covering', () => {
    const tracker = new BreakTracker(new WebBreakMarks());
    tracker.setMode('SCREEN');
    tracker.onRanges({ ad1: range('ad1') });
    assert.equal(tracker.update(START + 60_000).covered, true);

    tracker.onRanges({});
    assert.deepEqual(tracker.update(START + 60_000), NO_BREAK);
});

test('playing the ads leaves the picture uncovered and the sound on', () => {
    const tracker = new BreakTracker(new WebBreakMarks());
    tracker.setMode('PLAY');
    tracker.onRanges({ ad1: range('ad1', { DURATION: '90' }) });
    const view = tracker.update(START + 1_000);
    assert.equal(view.covered, false);
    assert.equal(view.muted, false);
});

test('the muted cover silences the break, and Show picture lifts both until the break ends', () => {
    const tracker = new BreakTracker(new WebBreakMarks());
    tracker.setMode('SCREEN_MUTED');
    tracker.onRanges({ ad1: range('ad1', { DURATION: '90' }), ad2: { attr: { ...range('ad2', { DURATION: '30' }).attr, 'START-DATE': '2026-11-04T12:10:00.000Z' } } });

    assert.equal(tracker.update(START + 1_000).muted, true);
    tracker.showPicture();
    const shown = tracker.update(START + 2_000);
    assert.equal(shown.covered, false);
    assert.equal(shown.muted, false);
    assert.equal(shown.pictureShown, true);

    // Out of the break and into the next one, the cover is back.
    tracker.update(START + 5 * 60_000);
    const next = tracker.update(START + 10 * 60_000 + 1_000);
    assert.equal(next.covered, true);
    assert.equal(next.pictureShown, false);
});

test('without a playlist date there is no break', () => {
    const tracker = new BreakTracker(new WebBreakMarks());
    tracker.setMode('SCREEN');
    tracker.onRanges({ ad1: range('ad1', { DURATION: '90' }) });
    assert.deepEqual(tracker.update(null), NO_BREAK);
});

test('the countdown reads minutes and seconds, rounded up', () => {
    assert.equal(countdown(65_000), '1:05');
    assert.equal(countdown(100), '0:01');
    assert.equal(countdown(0), '0:00');
});
