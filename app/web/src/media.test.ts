import assert from 'node:assert/strict';
import { test } from 'node:test';
import { startPlaying } from './media';

async function settle(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 0));
}

test('a play interrupted by a pause or a new load is not a refusal and raises nothing', async () => {
    let blocked = 0;
    for (const reason of ['interrupted by a call to pause()', 'interrupted by a new load request']) {
        startPlaying({ play: () => Promise.reject(new DOMException(reason, 'AbortError')) }, () => blocked++);
    }
    await settle();
    assert.equal(blocked, 0);
});

test('a play the browser refuses reports it', async () => {
    let blocked = 0;
    startPlaying({ play: () => Promise.reject(new DOMException('no gesture', 'NotAllowedError')) }, () => blocked++);
    await settle();
    assert.equal(blocked, 1);
});

test('a play that starts reports nothing', async () => {
    let blocked = 0;
    startPlaying({ play: () => Promise.resolve() }, () => blocked++);
    await settle();
    assert.equal(blocked, 0);
});
