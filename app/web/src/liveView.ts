import type { WebChannel } from '@sofaking/core';
import { BreakTracker, countdown, type BreakView, type LiveBreakMode } from './breaks';
import { button, h } from './dom';
import { setKeyHandler } from './focus';
import { nowNext } from './guide';
import { LivePlayback } from './live';
import { Watches, type Context, type View } from './view';

/** A live channel: the picture, what is on, the controls, and the cover that hides an ad break when Settings says so. */
export function liveView(ctx: Context, channelId: string): View {
    const watches = new Watches();
    const video = h('video', { attrs: { playsinline: true, autoplay: true, 'aria-label': 'Live picture' } });
    const title = h('span', { class: 'title' });
    const status = h('span', { class: 'status', attrs: { role: 'status' } });
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
        status,
        captions,
        button('Full screen', () => toggleFullScreen(el)),
    );
    const el = h('section', { class: 'player live' }, video, cover, pill, controls);

    const tracker = new BreakTracker({
        addLine: (tag) => marks.addLine(tag),
        at: (ms) => marks.at(ms),
    });
    const marks = ctx.core.decisions.breakMarks();
    let channel: WebChannel | null = null;
    let wasCovered = false;

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
    playback.onStatus = (text) => {
        status.textContent = text;
    };

    watches.add(ctx.core.guide.watchLineup((lineup) => {
        channel = lineup.channels.find((candidate) => candidate.id === channelId) ?? null;
        title.textContent = channel ? `${channel.number} ${channel.name}` : '';
    }));
    watches.add(ctx.core.guide.watchSettings((settings) => {
        tracker.setMode(settings.liveBreaks as LiveBreakMode);
        playback.setCaptions(settings.captionsEnabled);
        captions.setAttribute('aria-pressed', String(settings.captionsEnabled));
        captions.textContent = settings.captionsEnabled ? 'Captions on' : 'Captions off';
    }));
    void playback.start(channelId);
    video.focus();
    setKeyHandler((event) => {
        if (event.key === 'f') {
            toggleFullScreen(el);
            return true;
        }
        if (event.key === ' ' && event.target === video) {
            if (video.paused) void video.play();
            else video.pause();
            return true;
        }
        return false;
    });
    // A closing page cannot wait for the release to finish; a keepalive request outlives it.
    const onPageHide = () => sendRelease(ctx);
    window.addEventListener('pagehide', onPageHide);

    return {
        el,
        dispose() {
            setKeyHandler(null);
            window.removeEventListener('pagehide', onPageHide);
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
