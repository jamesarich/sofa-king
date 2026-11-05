// The pairing code the admin portal's Open the web app puts in the page's fragment.

export interface PairingRequest {
    serverUrl: string;
    code: string;
}

/** What the page needs of core's `WebText`. */
export interface FragmentParser {
    parsePairingFragment(fragment: string, pageOrigin: string): PairingRequest | null | undefined;
}

export interface PageLocation {
    hash: string;
    origin: string;
    pathname: string;
    search: string;
}

/**
 * The request the fragment holds, for the server that serves this page. The fragment is cleared at once, so a reload
 * or a bookmark does not claim the spent code again.
 */
export function takePairing(
    text: FragmentParser,
    location: PageLocation,
    history: { replaceState(data: unknown, unused: string, url: string): void },
): PairingRequest | null {
    const request = text.parsePairingFragment(location.hash, location.origin);
    if (!request) return null;
    history.replaceState(null, '', location.pathname + location.search);
    return request;
}

/** Why a pairing failed, in the words Settings shows; the names are core's `PairingFailure`. */
export function pairingMessage(failure: string): string {
    switch (failure) {
        case 'INVALID_CODE':
            return 'That code was not accepted. Check the six digits on the portal.';
        case 'OLDER_SERVER':
            return 'This server is too old to pair. Enter its API token instead.';
        default:
            return 'No server answered at that address.';
    }
}
