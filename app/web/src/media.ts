// Starting a media element from a key press.

/**
 * Plays [media] and calls [onBlocked] when the browser refuses. A `play()` the page itself interrupts, with a
 * `pause()` or a new load as the player closes or reopens, rejects with an `AbortError` nobody is waiting for:
 * it is not a refusal, and left unhandled it is a page error.
 */
export function startPlaying(media: Pick<HTMLMediaElement, 'play'>, onBlocked: () => void): void {
    media.play().catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === 'AbortError')) onBlocked();
    });
}
