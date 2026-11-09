import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
    bannerText,
    BANNER_MS,
    ChannelHistory,
    channelForDigits,
    DIGIT_COMMIT_MS,
    DigitEntry,
    Fade,
    liveKey,
    neighbor,
    stepVolume,
    ZAP_SETTLE_MS,
    ZapSettle,
    type Timers,
} from './zap';
import { StatusLine } from './status';
import { fieldKey, inputKeepsKey } from './focus';

/** Timers the test advances by hand. */
class FakeTimers implements Timers {
    now = 0;
    private next = 1;
    private pending = new Map<number, { at: number; run: () => void }>();

    set(run: () => void, ms: number): unknown {
        const id = this.next++;
        this.pending.set(id, { at: this.now + ms, run });
        return id;
    }

    clear(handle: unknown): void {
        this.pending.delete(handle as number);
    }

    advance(ms: number): void {
        const until = this.now + ms;
        for (;;) {
            const due = [...this.pending.entries()].filter(([, t]) => t.at <= until).sort((a, b) => a[1].at - b[1].at)[0];
            if (!due) break;
            this.pending.delete(due[0]);
            this.now = due[1].at;
            due[1].run();
        }
        this.now = until;
    }
}

const channels = [
    { id: 'a', number: 2, name: 'Two' },
    { id: 'b', number: 5, name: 'Five' },
    { id: 'c', number: 57, name: 'Fifty-seven' },
];

test('Up and Down are the channel above and below, Page Up and Page Down the higher and lower number', () => {
    assert.deepEqual(liveKey('ArrowUp', true), { kind: 'zap', step: -1 });
    assert.deepEqual(liveKey('ArrowDown', true), { kind: 'zap', step: 1 });
    assert.deepEqual(liveKey('PageUp', true), { kind: 'zap', step: 1 });
    assert.deepEqual(liveKey('PageDown', true), { kind: 'zap', step: -1 });
});

test('Up, Down, Enter and Space belong to a focused button, not the channel', () => {
    for (const key of ['ArrowUp', 'ArrowDown', 'Enter', ' ']) assert.equal(liveKey(key, false), null, key);
    assert.deepEqual(liveKey('PageUp', false), { kind: 'zap', step: 1 });
});

test('digits, last channel, volume, mute, captions and full screen have keys that leave the arrows to focus', () => {
    assert.deepEqual(liveKey('7', true), { kind: 'digit', digit: 7 });
    assert.deepEqual(liveKey('l', true), { kind: 'last' });
    assert.deepEqual(liveKey('m', true), { kind: 'mute' });
    assert.deepEqual(liveKey('+', true), { kind: 'volume', step: 1 });
    assert.deepEqual(liveKey('=', true), { kind: 'volume', step: 1 });
    assert.deepEqual(liveKey('-', true), { kind: 'volume', step: -1 });
    assert.deepEqual(liveKey('c', true), { kind: 'captions' });
    assert.deepEqual(liveKey('f', true), { kind: 'fullscreen' });
    assert.equal(liveKey('ArrowLeft', true), null);
    assert.equal(liveKey('ArrowRight', true), null);
});

test('zapping wraps around the ends of the lineup', () => {
    assert.equal(neighbor(channels, 'c', 1)?.id, 'a');
    assert.equal(neighbor(channels, 'a', -1)?.id, 'c');
    assert.equal(neighbor(channels, 'zzz', 1)?.id, 'a');
    assert.equal(neighbor([], 'a', 1), null);
});

test('a number finds the channel with that displayed number', () => {
    assert.equal(channelForDigits(channels, '57')?.id, 'c');
    assert.equal(channelForDigits(channels, '9'), null);
    assert.equal(channelForDigits(channels, ''), null);
});

test('volume steps by five and stays between zero and full', () => {
    assert.equal(stepVolume(0.5, 1), 0.55);
    assert.equal(stepVolume(0.98, 1), 1);
    assert.equal(stepVolume(0.02, -1), 0);
    let v = 0;
    for (let i = 0; i < 7; i++) v = stepVolume(v, 1);
    assert.equal(v, 0.35);
});

test('typed digits tune after the settle, and each digit restarts it', () => {
    const timers = new FakeTimers();
    const shown: string[] = [];
    const tuned: string[] = [];
    const entry = new DigitEntry((d) => shown.push(d), (d) => tuned.push(d), timers);
    entry.append(5);
    timers.advance(DIGIT_COMMIT_MS - 1);
    entry.append(7);
    timers.advance(DIGIT_COMMIT_MS - 1);
    assert.deepEqual(tuned, []);
    timers.advance(1);
    assert.deepEqual(tuned, ['57']);
    assert.deepEqual(shown, ['5', '57', '']);
    assert.equal(entry.active, false);
});

test('Enter tunes typed digits at once and an empty buffer tunes nothing', () => {
    const timers = new FakeTimers();
    const tuned: string[] = [];
    const entry = new DigitEntry(() => {}, (d) => tuned.push(d), timers);
    entry.commit();
    entry.append(2);
    entry.commit();
    timers.advance(DIGIT_COMMIT_MS * 2);
    assert.deepEqual(tuned, ['2']);
});

test('a number takes at most five digits', () => {
    const entry = new DigitEntry(() => {}, () => {}, new FakeTimers());
    for (let i = 0; i < 8; i++) entry.append(1);
    assert.equal(entry.digits, '11111');
});

test('a run of zaps tunes once, on the last target, after the settle', () => {
    const timers = new FakeTimers();
    const targets: string[] = [];
    const settled: string[] = [];
    const zap = new ZapSettle<{ id: string }>((c) => targets.push(c.id), (c) => settled.push(c.id), timers);
    zap.press(channels, 'a', 1);
    timers.advance(ZAP_SETTLE_MS - 1);
    zap.press(channels, 'a', 1);
    timers.advance(ZAP_SETTLE_MS - 1);
    assert.deepEqual(settled, []);
    timers.advance(1);
    assert.deepEqual(targets, ['b', 'c']);
    assert.deepEqual(settled, ['c']);
});

test('the banner hides a few seconds after the last time it showed', () => {
    const timers = new FakeTimers();
    const states: boolean[] = [];
    const banner = new Fade((shown) => states.push(shown), BANNER_MS, timers);
    banner.show();
    timers.advance(BANNER_MS - 1);
    banner.show();
    timers.advance(BANNER_MS - 1);
    assert.deepEqual(states, [true, true]);
    timers.advance(1);
    assert.deepEqual(states, [true, true, false]);
});

test('the last channel is the one before the one on now, also across the guide', () => {
    const history = new ChannelHistory();
    assert.equal(history.last, null);
    history.watching('a');
    history.watching('b');
    assert.equal(history.last, 'a');
    history.watching('b');
    assert.equal(history.last, 'a');
    history.watching('a');
    assert.equal(history.last, 'b');
});

test('the banner names the channel, what is on with its hours, and what is next', () => {
    const text = bannerText(
        channels[1]!,
        0,
        { now: { title: 'News', startMs: 1, stopMs: 2 }, next: { title: 'Weather' } },
        (ms) => `t${ms}`,
    );
    assert.deepEqual(text, { heading: '5 Five', now: 'News, t1 to t2', next: 'Next: Weather' });
    assert.deepEqual(bannerText(channels[1]!, 0, { now: null, next: null }, String), { heading: '5 Five', now: '', next: '' });
});

test('the status line is hidden when empty and visible while it has text', () => {
    const target = { textContent: 'x', hidden: false };
    const status = new StatusLine(target);
    assert.equal(target.hidden, true);
    status.set('Reconnecting');
    assert.deepEqual(target, { textContent: 'Reconnecting', hidden: false });
    status.clearIf('Press Enter to play');
    assert.equal(target.textContent, 'Reconnecting');
    status.clearIf('Reconnecting');
    assert.equal(target.hidden, true);
});

test('Up and Down leave a text field, and a number or range field keeps its arrows', () => {
    assert.equal(inputKeepsKey('ArrowUp', 'text'), false);
    assert.equal(inputKeepsKey('ArrowDown', 'search'), false);
    assert.equal(inputKeepsKey('ArrowUp', 'number'), true);
    assert.equal(inputKeepsKey('ArrowDown', 'range'), true);
    assert.equal(inputKeepsKey('Backspace', 'text'), true);
    assert.equal(inputKeepsKey('a', 'text'), true);
});

test('Escape leaves a text field and Up and Down step out of it, except while an IME composes', () => {
    assert.equal(fieldKey('Escape', 'text', false), 'blur');
    assert.equal(fieldKey('ArrowDown', 'search', false), 'move');
    assert.equal(fieldKey('ArrowUp', 'text', true), 'stay');
    assert.equal(fieldKey('Escape', 'text', true), 'stay');
    assert.equal(fieldKey('a', 'text', false), 'stay');
});
