// What the guide and the library say while they have no list to show. Plain functions over the states core hands
// over, so they run without a browser.

export const SIGNED_OUT_TITLE = 'This device is signed out';
export const SIGNED_OUT_HINT =
    "The server no longer accepts this device. Open Settings and pair it again with a code from the server's admin portal.";

export interface LineupLike {
    state: string;
    message: string;
    guideLoading: boolean;
    signedOut: boolean;
}

export interface HomeLike {
    state: string;
    message: string;
    signedOut: boolean;
}

/** True when the server turned this device's token away, so the page says to pair again instead of showing a failure. */
export function isSignedOut(state: { state: string; signedOut: boolean } | null): boolean {
    return state?.state === 'failed' && state.signedOut;
}

export function guideStatus(lineup: LineupLike | null, shown: number, search: string): string {
    if (!lineup || lineup.state === 'loading') return 'Loading the guide';
    if (lineup.state === 'unconfigured') return 'No server yet. Open Settings to pair with one.';
    if (lineup.state === 'failed') return `The guide did not load: ${lineup.message}`;
    if (shown === 0) return search ? 'No channel or program matches.' : 'No channels here.';
    return lineup.guideLoading ? 'Loading programs' : '';
}

export function homeStatus(home: HomeLike): string {
    switch (home.state) {
        case 'unconfigured':
            return 'No server yet. Open Settings to pair with one.';
        case 'unavailable':
            return 'This server keeps no libraries.';
        case 'failed':
            return `The library did not load: ${home.message}`;
        default:
            return 'Loading the library';
    }
}
