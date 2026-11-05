import assert from 'node:assert/strict';
import { test } from 'node:test';
import { WebText } from '@sofaking/core';
import { pairingMessage, takePairing } from './pairing';

function page(hash: string) {
    const replaced: string[] = [];
    const location = { hash, origin: 'http://192.168.1.3:9193', pathname: '/app2/', search: '?x=1' };
    return { location, history: { replaceState: (_: unknown, __: string, url: string) => replaced.push(url) }, replaced };
}

test('a pair fragment names the server that serves the page and is cleared from the address', () => {
    const { location, history, replaced } = page('#pair=123456');
    assert.deepEqual(
        { ...takePairing(new WebText(), location, history) },
        { serverUrl: 'http://192.168.1.3:9193', code: '123456' },
    );
    assert.deepEqual(replaced, ['/app2/?x=1']);
});

test('another fragment, or a code that is not six digits, pairs nothing and leaves the address alone', () => {
    for (const hash of ['', '#/guide', '#pair=12345', '#pair=abcdef']) {
        const { location, history, replaced } = page(hash);
        assert.equal(takePairing(new WebText(), location, history), null, hash);
        assert.deepEqual(replaced, [], hash);
    }
});

test('each failure has its own message', () => {
    const messages = new Set(['INVALID_CODE', 'OLDER_SERVER', 'UNREACHABLE'].map(pairingMessage));
    assert.equal(messages.size, 3);
});
