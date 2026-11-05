import type { WebSettingsState } from '@sofaking/core';
import { button, h } from './dom';
import { pairingMessage } from './pairing';
import { Watches, type Context, type View } from './view';

const BREAK_CHOICES: Array<[string, string]> = [
    ['PLAY', 'Play the ads'],
    ['SCREEN_MUTED', 'Cover and mute them'],
    ['SCREEN', 'Cover them'],
];

/** What the app says about the pairing it tried on opening, until Settings shows it. */
let pairingNote = '';

export function noteFromPairing(message: string): void {
    pairingNote = message;
}

/** Settings: the server (address, a pairing code or a token), the ad breaks, captions. */
export function settingsView(ctx: Context): View {
    const watches = new Watches();
    const url = h('input', { attrs: { type: 'url', id: 'server-url', placeholder: 'http://192.168.1.10:9193', autocomplete: 'off' } });
    const token = h('input', { attrs: { type: 'password', id: 'server-token', autocomplete: 'off' } });
    const code = h('input', { attrs: { type: 'text', id: 'pair-code', inputmode: 'numeric', maxlength: 6, placeholder: '123456', autocomplete: 'off' } });
    const message = h('p', { class: 'status', text: pairingNote, attrs: { role: 'status' } });
    const breaks = h('select', { attrs: { id: 'breaks' } }, ...BREAK_CHOICES.map(([value, label]) => h('option', { text: label, attrs: { value } })));
    const captions = h('input', { attrs: { type: 'checkbox', id: 'captions' } });
    const tokenNote = h('span', { class: 'hint' });
    let current: WebSettingsState | null = null;
    let filled = false;

    const pair = async () => {
        const server = url.value.trim() || current?.serverUrl || window.location.origin;
        message.textContent = 'Pairing';
        const failure = await ctx.core.settings.pair(server, code.value);
        message.textContent = failure ? pairingMessage(failure) : 'Paired.';
        if (!failure) code.value = '';
    };
    const save = () => {
        ctx.core.settings.saveServer(url.value.trim(), token.value === '' && current?.tokenSet ? null : token.value);
        token.value = '';
        message.textContent = 'Saved.';
    };

    breaks.addEventListener('change', () => ctx.core.settings.setLiveBreaks(breaks.value));
    captions.addEventListener('change', () => ctx.core.settings.setCaptions(captions.checked));

    watches.add(ctx.core.guide.watchSettings((settings) => {
        current = settings;
        // The address is the viewer's to type in; it fills once from what is saved.
        if (!filled) {
            filled = true;
            url.value = settings.serverUrl;
        }
        tokenNote.textContent = settings.tokenSet ? 'A token is saved; leave this empty to keep it.' : 'Leave empty for a server without one.';
        breaks.value = settings.liveBreaks;
        captions.checked = settings.captionsEnabled;
    }));

    const el = h(
        'section',
        { class: 'settings' },
        h('h1', { text: 'Settings' }),
        h(
            'fieldset',
            {},
            h('legend', { text: 'Server' }),
            h('label', { attrs: { for: 'server-url' }, text: 'Address' }),
            url,
            h('label', { attrs: { for: 'server-token' }, text: 'API token' }),
            token,
            tokenNote,
            button('Save', save),
            h('label', { attrs: { for: 'pair-code' }, text: 'Pairing code from the portal' }),
            code,
            button('Pair', () => void pair()),
            message,
        ),
        h(
            'fieldset',
            {},
            h('legend', { text: 'Live TV' }),
            h('label', { attrs: { for: 'breaks' }, text: 'During ads' }),
            breaks,
            h('label', { class: 'check' }, captions, ' Captions'),
        ),
    );
    pairingNote = '';
    return { el, dispose: () => watches.cancel() };
}
