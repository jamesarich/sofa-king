// The grid's row of first letters, the way the Compose apps show it.

/** One letter of the row: the first item under it is at [offset] in the grid. */
export interface Letter {
    letter: string;
    offset: number;
}

const LETTERS_MIN_ITEMS = 80;
const LETTERS_MIN_LETTERS = 6;

/** A row of letters helps a grid in title order long enough to scroll a while, with enough letters to choose from. */
export function showsLetters(sort: string, total: number, letters: readonly Letter[]): boolean {
    return sort === 'title' && total >= LETTERS_MIN_ITEMS && letters.length >= LETTERS_MIN_LETTERS;
}

/** The count under the bar: "1 title" or "1,240 titles". */
export function titleCount(total: number): string {
    return `${total.toLocaleString('en-US')} ${total === 1 ? 'title' : 'titles'}`;
}
