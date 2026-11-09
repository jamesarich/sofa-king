import assert from 'node:assert/strict';
import { test } from 'node:test';
import { guideStatus, homeStatus, isSignedOut } from './messages';

const lineup = (state: string, signedOut = false) => ({ state, message: 'HTTP 401', guideLoading: false, signedOut });

test('a failure the server answered with a rejected token is signed out, any other failure is not', () => {
    assert.equal(isSignedOut(lineup('failed', true)), true);
    assert.equal(isSignedOut(lineup('failed', false)), false);
    assert.equal(isSignedOut(lineup('ready', true)), false);
    assert.equal(isSignedOut(null), false);
});

test('the guide names its failure and the library its own', () => {
    assert.equal(guideStatus(lineup('failed'), 0, ''), 'The guide did not load: HTTP 401');
    assert.equal(homeStatus({ state: 'failed', message: 'HTTP 403', signedOut: false }), 'The library did not load: HTTP 403');
});

test('the guide says what an empty list means', () => {
    assert.equal(guideStatus(null, 0, ''), 'Loading the guide');
    assert.equal(guideStatus(lineup('unconfigured'), 0, ''), 'No server yet. Open Settings to pair with one.');
    assert.equal(guideStatus(lineup('ready'), 0, 'xyz'), 'No channel or program matches.');
    assert.equal(guideStatus(lineup('ready'), 0, ''), 'No channels here.');
    assert.equal(guideStatus(lineup('ready'), 3, ''), '');
});
