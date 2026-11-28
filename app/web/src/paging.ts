// When a grid loads its next page by itself: as the reader nears its end, the way the Compose apps' grids do.

export interface PagedGrid {
    hasMore: boolean;
    loading: boolean;
    error?: string | null;
}

/** How far below the window the grid's end may be and still count as near, in CSS pixels: about two rows of cards. */
export const NEAR_END_PX = 800;

/** The grid's end, [endTop] from the top of a window [windowHeight] tall, is near enough to load the next page. */
export function nearEnd(endTop: number, windowHeight: number): boolean {
    return endTop <= windowHeight + NEAR_END_PX;
}

/** The next page loads by itself while there is one, none is on its way and the last one did not fail. */
export function loadsItself(grid: PagedGrid, near: boolean): boolean {
    return near && grid.hasMore && !grid.loading && !grid.error;
}

/**
 * What the button under the grid says, or null when it stays away: Try again after a page failed, and Load more in a
 * browser that cannot watch the grid's end ([watched] false).
 */
export function moreLabel(grid: PagedGrid, watched: boolean): string | null {
    if (grid.loading) return null;
    if (grid.error) return 'Try again';
    return grid.hasMore && !watched ? 'Load more' : null;
}

/**
 * True when [next] is what the grid [shown] already draws with more cards after it, so only those are added; a grid
 * that changed anywhere else is drawn again. Each entry is what a card shows of its item (see `cardKey`).
 */
export function extendsShown(shown: readonly string[], next: readonly string[]): boolean {
    return shown.length > 0 && next.length >= shown.length && shown.every((key, index) => key === next[index]);
}
