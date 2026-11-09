import { h } from './dom';
import { SIGNED_OUT_HINT, SIGNED_OUT_TITLE } from './messages';
import { href } from './router';

/** What a page shows when the server turned this device's token away; [withLink] adds the way to Settings. */
export function signedOutNotice(withLink: boolean): HTMLElement {
    return h(
        'div',
        { class: 'signed-out', attrs: { role: 'alert' } },
        h('h2', { text: SIGNED_OUT_TITLE }),
        h('p', { class: 'status', text: SIGNED_OUT_HINT }),
        withLink
            ? h('a', { class: 'chip on', text: 'Open Settings', attrs: { href: href({ page: 'settings' }), 'data-key': 'signed-out:settings' } })
            : null,
    );
}
