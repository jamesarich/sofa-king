import type { WebStream } from '@sofaking/core';
import Hls from 'hls.js';
import { button, h } from './dom';
import { setKeyHandler } from './focus';
import { sendRelease, toggleFullScreen } from './liveView';
import type { Context, View } from './view';

const SAVE_EVERY_MS = 10_000;
const SEEK_STEP_S = 10;

/**
 * A library item or recording: the server's HLS through hls.js (or the browser's own where it has no MediaSource), or
 * the file as it is in the `<video>`. Progress goes to core, which saves it; a seek past what a conversion has written
 * restarts the conversion there.
 */
export function vodView(ctx: Context, itemId: string, fromStart: boolean): View {
    const video = h('video', { attrs: { playsinline: true, controls: true, autoplay: true, 'aria-label': 'Picture' } });
    const status = h('span', { class: 'status', attrs: { role: 'status' } });
    const el = h(
        'section',
        { class: 'player vod' },
        video,
        h(
            'div',
            { class: 'controls' },
            button('Back', ctx.back),
            button('Back 10 s', () => seekBy(-SEEK_STEP_S)),
            button('Forward 10 s', () => seekBy(SEEK_STEP_S)),
            button('Full screen', () => toggleFullScreen(el)),
            status,
        ),
    );

    let stream: WebStream | null = null;
    let hls: Hls | null = null;
    let disposed = false;
    let waited = 0;
    let lastSaved = 0;

    /** Where the item is now, in milliseconds. */
    const itemMs = () => (stream?.offsetMs ?? 0) + video.currentTime * 1000;
    const durationMs = () => stream?.itemDurationMs ?? (Number.isFinite(video.duration) ? video.duration * 1000 : 0);
    const writtenMs = () => (video.seekable.length > 0 ? video.seekable.end(video.seekable.length - 1) * 1000 : null);

    function load(next: WebStream, startSeconds: number): void {
        stream = next;
        hls?.destroy();
        hls = null;
        const start = Math.max(0, startSeconds);
        if (next.mode !== 'direct' && Hls.isSupported()) {
            const player = new Hls({ startPosition: start > 0 ? start : -1 });
            player.on(Hls.Events.ERROR, (_event, data) => {
                if (data.fatal) status.textContent = 'The picture stopped. Go back and play it again.';
            });
            hls = player;
            player.loadSource(next.url);
            player.attachMedia(video);
        } else {
            video.src = next.url;
            if (start > 0) video.addEventListener('loadedmetadata', () => (video.currentTime = start), { once: true });
        }
        video.play().catch(() => (status.textContent = 'Press Enter to play'));
    }

    async function open(): Promise<void> {
        try {
            const next = await ctx.core.item.play(itemId, fromStart);
            if (disposed) return;
            waited = 0;
            status.textContent = '';
            load(next, (next.startAtMs - next.offsetMs) / 1000);
        } catch (error) {
            if (disposed) return;
            const delay = ctx.core.decisions.isConverterBusy(error) ? (ctx.core.decisions.converterRetryDelayMs(waited) ?? null) : null;
            if (delay === null) {
                status.textContent = 'This item did not open.';
                return;
            }
            status.textContent = 'Every converter is busy; trying again';
            waited += delay;
            setTimeout(() => void open(), delay);
        }
    }

    async function seekTo(ms: number): Promise<void> {
        const plan = ctx.core.decisions.vodSeek(ms, writtenMs(), durationMs() || null);
        if (!plan.restart) {
            video.currentTime = plan.ms / 1000;
            return;
        }
        const restarted = await ctx.core.item.restartAt(plan.ms);
        if (restarted && !disposed) load(restarted, 0);
    }

    function seekBy(seconds: number): void {
        void seekTo(Math.max(0, itemMs() + seconds * 1000));
    }

    video.addEventListener('timeupdate', () => {
        status.textContent = status.textContent?.startsWith('Press') ? '' : status.textContent;
        const now = Date.now();
        if (now - lastSaved < SAVE_EVERY_MS) return;
        lastSaved = now;
        ctx.core.item.progress(itemId, itemMs(), durationMs());
    });
    video.addEventListener('ended', () => ctx.back());
    video.addEventListener('error', () => {
        if (!disposed && !hls) status.textContent = 'The picture stopped.';
    });

    void open();
    video.focus();
    setKeyHandler((event) => {
        if (event.target instanceof HTMLButtonElement && event.key === ' ') return false;
        switch (event.key) {
            case ' ':
                if (video.paused) void video.play();
                else video.pause();
                return true;
            case 'ArrowLeft':
                seekBy(-SEEK_STEP_S);
                return true;
            case 'ArrowRight':
                seekBy(SEEK_STEP_S);
                return true;
            case 'f':
                toggleFullScreen(el);
                return true;
            default:
                return false;
        }
    });
    const onPageHide = () => sendRelease(ctx);
    window.addEventListener('pagehide', onPageHide);

    return {
        el,
        dispose() {
            disposed = true;
            setKeyHandler(null);
            window.removeEventListener('pagehide', onPageHide);
            if (stream) ctx.core.item.stopped(itemId, itemMs(), durationMs());
            hls?.destroy();
            video.pause();
            video.removeAttribute('src');
            video.load();
            ctx.core.channels.stopWatching();
        },
    };
}
