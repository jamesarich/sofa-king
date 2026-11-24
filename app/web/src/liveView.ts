import type { WebChannel, WebSettingsState } from '@sofaking/core';
import { BreakTracker, countdown, type BreakView, type LiveBreakMode } from './breaks';
import { button, h } from './dom';
import { setKeyHandler } from './focus';
import { clock, filterChannels, nowNext } from './guide';
import { LivePlayback } from './live';
import { startPlaying } from './media';
import { StatusLine } from './status';
import { Watches, type Context, type View } from './view';
import {
    BANNER_MS,
    bannerText,
    ChannelHistory,
    channelForDigits,
    DigitEntry,
    Fade,
    liveKey,
    NUMBER_MISS_MS,
    stepVolume,
    ZapSettle,
} from './zap';

/** The channels watched in this page, so a key returns to the one before. */
const watched = new ChannelHistory();
/** The picture's volume and mute outlive the `<video>`, which each channel makes anew. */
const sound = { volume: 1, muted: false };
const FLASH_MS = 1_500;

/** A live channel: the picture, what is on, the controls, and the cover that hides an ad break when Settings says so. */
export function liveView(ctx: Context, channelId: string): View {
    watched.watching(channelId);
    const watches = new Watches();
    const video = h('video', { attrs: { playsinline: true, autoplay: true, tabindex: -1, 'aria-label': 'Live picture' } });
    video.volume = sound.volume;
    const title = h('span', { class: 'title' });
    const statusEl = h('p', { class: 'player-status', attrs: { role: 'status' } });
    const status = new StatusLine(statusEl);
    const bannerHeading = h('p', { class: 'banner-heading' });
    const bannerNow = h('p', { class: 'banner-now' });
    const bannerNext = h('p', { class: 'banner-next' });
    const banner = h('div', { class: 'channel-banner', attrs: { 'aria-live': 'polite' } }, bannerHeading, bannerNow, bannerNext);
    const toast = h('p', { class: 'player-toast', attrs: { role: 'status' } });
    const breakTitle = h('p', { class: 'break-title' });
    const breakLeft = h('p', { class: 'break-left' });
    const showPicture = button('Show picture', () => {
        tracker.showPicture();
        playback.setCaptions(playback.captions);
    });
    const cover = h(
        'div',
        { class: 'break', attrs: { hidden: true, role: 'dialog', 'aria-label': 'Ad break' } },
        h('p', { class: 'break-kicker', text: 'Ad break' }),
        breakTitle,
        breakLeft,
        showPicture,
    );
    const pill = h('div', { class: 'break-pill', attrs: { hidden: true } });
    const captions = button('Captions', () => ctx.core.settings.setCaptions(!playback.captions));
    const controls = h(
        'div',
        { class: 'controls' },
        button('Back', ctx.back),
        title,
        captions,
        button('Full screen', () => toggleFullScreen(el)),
    );
    const el = h('section', { class: 'player live' }, video, cover, pill, banner, toast, statusEl, controls);

    const tracker = new BreakTracker({
        replaceLines: (tags) => marks.replaceLines(tags),
        at: (ms) => marks.at(ms),
    });
    const marks = ctx.core.decisions.breakMarks();
    let channel: WebChannel | null = null;
    let lineup: readonly WebChannel[] = [];
    let settings: WebSettingsState | null = null;
    let wasCovered = false;

    const bannerFade = new Fade((shown) => banner.classList.toggle('shown', shown), BANNER_MS);
    // One fade for the toast, so a volume flash and a missed number replace each other's hide timer.
    const toastFade = new Fade((shown) => toast.classList.toggle('shown', shown), FLASH_MS);

    const showBanner = (of: WebChannel) => {
        const now = Date.now();
        const text = bannerText(of, now, nowNext(of, now), clock);
        bannerHeading.textContent = text.heading;
        bannerNow.textContent = text.now;
        bannerNext.textContent = text.next;
        bannerFade.show();
    };
    const flash = (text: string) => {
        toast.textContent = text;
        toastFade.show();
    };
    const tuneTo = (id: string) => {
        if (id !== channelId) ctx.replace({ page: 'watch', id });
    };

    /** Channel up and down stay inside the guide's filter while the channel is in it. */
    const zapList = (): readonly WebChannel[] => {
        if (settings) {
            const kept = filterChannels(lineup, settings.guideFilter ?? null, {
                favorites: settings.favorites,
                recentChannels: settings.recentChannels,
            });
            if (kept.some((candidate) => candidate.id === channelId)) return kept;
        }
        return filterChannels(lineup, null, { favorites: [], recentChannels: [] });
    };

    const zap = new ZapSettle<WebChannel>(showBanner, (target) => tuneTo(target.id));
    const digits = new DigitEntry(
        (typed) => {
            if (typed === '') return;
            toastFade.cancel();
            toast.textContent = typed;
            toast.classList.add('shown');
        },
        (typed) => {
            const found = channelForDigits(lineup, typed);
            if (found) {
                toast.classList.remove('shown');
                if (found.id === channelId) showBanner(found);
                else tuneTo(found.id);
            } else {
                toast.textContent = `No channel ${typed}`;
                toastFade.show(NUMBER_MISS_MS);
            }
        },
    );

    const onBreak = (view: BreakView) => {
        cover.hidden = !view.covered;
        pill.hidden = !view.pictureShown;
        const now = channel ? nowNext(channel, Date.now()).now : null;
        breakTitle.textContent = now ? now.title : (channel?.name ?? '');
        const left = view.remainingMs === null ? 'Back soon' : `Back in ${countdown(view.remainingMs)}`;
        breakLeft.textContent = left;
        pill.textContent = `Ad break, ${left.toLowerCase()}`;
        if (view.covered && !wasCovered) showPicture.focus();
        wasCovered = view.covered;
    };
    const playback = new LivePlayback(ctx.core, video, tracker, onBreak);
    playback.userMuted = sound.muted;
    video.muted = sound.muted;
    playback.onStatus = (text) => status.set(text);

    watches.add(ctx.core.guide.watchLineup((next) => {
        const first = channel === null;
        lineup = next.channels;
        channel = lineup.find((candidate) => candidate.id === channelId) ?? null;
        title.textContent = channel ? `${channel.number} ${channel.name}` : '';
        if (channel && first) showBanner(channel);
    }));
    watches.add(ctx.core.guide.watchSettings((next) => {
        settings = next;
        tracker.setMode(next.liveBreaks as LiveBreakMode);
        playback.setCaptions(next.captionsEnabled);
        captions.setAttribute('aria-pressed', String(next.captionsEnabled));
        captions.textContent = next.captionsEnabled ? 'Captions on' : 'Captions off';
    }));
    void playback.start(channelId);
    setKeyHandler((event) => {
        const onPicture = event.target === video || event.target === document.body;
        const key = liveKey(event.key, onPicture);
        if (!key) return false;
        switch (key.kind) {
            case 'fullscreen':
                toggleFullScreen(el);
                return true;
            case 'pause':
                if (video.paused) startPlaying(video, () => status.set('Press Enter to play'));
                else video.pause();
                return true;
            case 'zap':
                zap.press(zapList(), channelId, key.step);
                return true;
            case 'digit':
                digits.append(key.digit);
                return true;
            case 'commit':
                if (!digits.active) return false;
                digits.commit();
                return true;
            case 'last':
                if (watched.last) tuneTo(watched.last);
                return true;
            case 'captions':
                ctx.core.settings.setCaptions(!playback.captions);
                return true;
            case 'mute':
                sound.muted = !sound.muted;
                playback.userMuted = sound.muted;
                video.muted = sound.muted;
                flash(sound.muted ? 'Muted' : `Volume ${Math.round(sound.volume * 100)}%`);
                return true;
            case 'volume':
                sound.volume = stepVolume(sound.volume, key.step);
                sound.muted = false;
                playback.userMuted = false;
                video.muted = false;
                video.volume = sound.volume;
                flash(`Volume ${Math.round(sound.volume * 100)}%`);
                return true;
        }
    });
    // A closing page cannot wait for the release to finish; a keepalive request outlives it.
    const onPageHide = () => sendRelease(ctx);
    window.addEventListener('pagehide', onPageHide);

    return {
        el,
        focus: () => video.focus(),
        dispose() {
            setKeyHandler(null);
            window.removeEventListener('pagehide', onPageHide);
            zap.cancel();
            digits.cancel();
            bannerFade.cancel();
            toastFade.cancel();
            watches.cancel();
            playback.dispose();
        },
    };
}

export function sendRelease(ctx: Context): void {
    const release = ctx.core.channels.exitRelease();
    if (!release) return;
    const headers: Record<string, string> = release.token ? { Authorization: `Bearer ${release.token}` } : {};
    void fetch(release.url, { method: 'DELETE', keepalive: true, headers }).catch(() => {});
}

export function toggleFullScreen(target: HTMLElement): void {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void target.requestFullscreen?.().catch(() => {});
}
