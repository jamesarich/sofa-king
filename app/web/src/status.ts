// The player's status line ("Reconnecting", "Press Enter to play", an error): an element of its own that is always
// visible while it has text, whether or not the controls are showing.

export interface StatusTarget {
    textContent: string | null;
    hidden: boolean | string;
}

export class StatusLine {
    constructor(private readonly target: StatusTarget) {
        this.set('');
    }

    set(text: string): void {
        this.target.textContent = text;
        this.target.hidden = text === '';
    }

    get text(): string {
        return this.target.textContent ?? '';
    }

    /** Clears the status when it reads [text]; a later message is left alone. */
    clearIf(text: string): void {
        if (this.text === text) this.set('');
    }
}
