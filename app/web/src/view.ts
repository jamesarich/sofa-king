import type { SofaKingWeb, Watch } from '@sofaking/core';
import type { Route } from './router';

/** What a page draws into and how it lets go: it cancels its watches and stops what it plays. */
export interface View {
    el: HTMLElement;
    dispose(): void;
}

export interface Context {
    core: SofaKingWeb;
    go(route: Route): void;
    back(): void;
}

/** The subscriptions a page holds, canceled together when it goes. */
export class Watches {
    private held: Watch[] = [];

    add(watch: Watch): void {
        this.held.push(watch);
    }

    cancel(): void {
        for (const watch of this.held) watch.cancel();
        this.held = [];
    }
}
