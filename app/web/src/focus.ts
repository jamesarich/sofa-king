// Keyboard use: Tab as the browser has it, the arrow keys to the nearest control in that direction, Enter on a focused
// control, Escape and Backspace to go back. A player claims keys first through setKeyHandler.

export type Direction = 'up' | 'down' | 'left' | 'right';

const FOCUSABLE = 'button, a[href], input, select, textarea, [tabindex]:not([tabindex="-1"])';
const ARROWS: Record<string, Direction> = {
    ArrowUp: 'up',
    ArrowDown: 'down',
    ArrowLeft: 'left',
    ArrowRight: 'right',
};

export function focusables(root: ParentNode): HTMLElement[] {
    return [...root.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((element) => {
        if (element.hasAttribute('disabled') || element.closest('[hidden]')) return false;
        const box = element.getBoundingClientRect();
        return box.width > 0 && box.height > 0;
    });
}

interface Point {
    x: number;
    y: number;
}

function center(element: Element): Point {
    const box = element.getBoundingClientRect();
    return { x: box.left + box.width / 2, y: box.top + box.height / 2 };
}

/**
 * The control to move to from [from] in [direction]: the nearest one whose center lies that way, straight ahead
 * counting for more than aside. Null when nothing lies that way.
 */
export function nextFocus(from: Point, candidates: Array<{ element: HTMLElement; at: Point }>, direction: Direction) {
    let best: HTMLElement | null = null;
    let bestScore = Infinity;
    for (const { element, at } of candidates) {
        const dx = at.x - from.x;
        const dy = at.y - from.y;
        const along = direction === 'left' ? -dx : direction === 'right' ? dx : direction === 'up' ? -dy : dy;
        const aside = direction === 'left' || direction === 'right' ? Math.abs(dy) : Math.abs(dx);
        if (along <= 1) continue;
        const score = along + aside * 3;
        if (score < bestScore) {
            bestScore = score;
            best = element;
        }
    }
    return best;
}

export function moveFocus(root: ParentNode, direction: Direction): boolean {
    const all = focusables(root);
    const active = document.activeElement as HTMLElement | null;
    if (!active || !all.includes(active)) {
        all[0]?.focus();
        return all.length > 0;
    }
    const at = center(active);
    const candidates = all.filter((element) => element !== active).map((element) => ({ element, at: center(element) }));
    const target = nextFocus(at, candidates, direction);
    target?.focus();
    target?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    return target !== null;
}

/** Focuses the first control in [root], or [preferred] when it is there; what a view calls once it has drawn. */
export function focusFirst(root: ParentNode, preferred?: string): void {
    const all = focusables(root);
    const wanted = preferred ? all.find((element) => element.matches(preferred)) : undefined;
    (wanted ?? all[0])?.focus();
}

/** Runs [render], which redraws [container], and puts focus back on the control that had it, by its `data-key`. */
export function keepFocus(container: HTMLElement, render: () => void): void {
    const active = document.activeElement;
    const key = active instanceof HTMLElement && container.contains(active) ? active.dataset.key : undefined;
    render();
    if (key === undefined) return;
    for (const element of focusables(container)) {
        if (element.dataset.key === key) {
            element.focus();
            return;
        }
    }
}

type KeyHandler =(event: KeyboardEvent) => boolean;
let claim: KeyHandler | null = null;

/** Lets the page on top take keys first; it returns true for a key it used. Null gives them back. */
export function setKeyHandler(handler: KeyHandler | null): void {
    claim = handler;
}

function typing(target: EventTarget | null): boolean {
    return target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement;
}

export function installKeys(root: HTMLElement, back: () => void): void {
    document.addEventListener('keydown', (event) => {
        if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
        if (claim?.(event)) {
            event.preventDefault();
            return;
        }
        if (event.key === 'Escape' || (event.key === 'Backspace' && !typing(event.target))) {
            if (typing(event.target)) (event.target as HTMLElement).blur();
            else back();
            event.preventDefault();
            return;
        }
        const direction = ARROWS[event.key];
        if (!direction || event.target instanceof HTMLSelectElement || typing(event.target)) return;
        if (moveFocus(root, direction)) event.preventDefault();
    });
}
