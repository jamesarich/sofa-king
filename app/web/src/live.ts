import type { SofaKingWeb, Watch } from '@sofaking/core';
import Hls from 'hls.js';
import { BreakTracker, NO_BREAK, type BreakView } from './breaks';

/** How often the picture's place on the playlist clock is read against the break marks. */
const POLL_MS = 500;

/** Applies [enabled] to the captions the stream carries; hls.js adds them to the video as text tracks. */
export function applyCaptions(video: HTMLVideoElement, enabled: boolean): void {
    for (const track of Array.from(video.textTracks)) {
        if (track.kind === 'captions' || track.kind === 'subtitles') track.mode = enabled ? 'showing' : 'disabled';
    }
}

/**
 * One live channel in a `<video>`: the session through core, the stream through hls.js (or the browser's own HLS
 * where hls.js has no MediaSource, as on Safari, where the break overlay stays off), the stream's ad date ranges into
 * core's break marks, and a retry with core's backoff when the stream fails.
 */
export class LivePlayback {
    private hls: Hls | null = null;
    private disposed = false;
    private attempt = 0;
    private retryTimer: ReturnType<typeof setTimeout> | undefined;
    private readonly poll: ReturnType<typeof setInterval>;
    private replaced: Watch;
    captions = false;
    userMuted = false;
    onStatus: (text: string) => void = () => {};

    constructor(
        private readonly core: SofaKingWeb,
        private readonly video: HTMLVideoElement,
        private readonly tracker: BreakTracker,
        private readonly onBreak: (view: BreakView) => void,
    ) {
        this.poll = setInterval(() => this.readBreak(), POLL_MS);
        this.replaced = core.channels.watchReplaced((stream) => this.load(stream.url));
        video.textTracks.addEventListener('addtrack', () => applyCaptions(video, this.captions));
        video.addEventListener('playing', () => {
            this.attempt = 0;
            this.onStatus('');
        });
        video.addEventListener('error', () => this.retry());
    }

    /** Opens the session for [channelId] and plays it. */
    async start(channelId: string): Promise<void> {
        this.channelId = channelId;
        await this.open();
    }

    private channelId = '';

    private async open(): Promise<void> {
        if (this.disposed) return;
        try {
            const stream = await this.core.channels.watch(this.channelId);
            if (!this.disposed) this.load(stream.url);
        } catch {
            this.retry();
        }
    }

    private load(url: string): void {
        if (this.disposed) return;
        this.hls?.destroy();
        this.hls = null;
        if (Hls.isSupported()) {
            const hls = new Hls();
            hls.subtitleDisplay = this.captions;
            hls.on(Hls.Events.LEVEL_UPDATED, (_event, data) => this.tracker.onRanges(data.details.dateRanges));
            hls.on(Hls.Events.ERROR, (_event, data) => {
                if (!data.fatal) return;
                hls.destroy();
                if (this.hls === hls) this.hls = null;
                this.retry();
            });
            this.hls = hls;
            hls.loadSource(url);
            hls.attachMedia(this.video);
        } else {
            this.video.src = url;
        }
        this.video.play().catch(() => this.onStatus('Press Enter to play'));
    }

    private retry(): void {
        if (this.disposed || this.retryTimer !== undefined) return;
        this.onStatus('Reconnecting');
        const delay = this.core.decisions.reconnectDelayMs(this.attempt++);
        this.retryTimer = setTimeout(() => {
            this.retryTimer = undefined;
            void this.open();
        }, delay);
    }

    private readBreak(): void {
        const date = this.hls?.playingDate ?? null;
        const view = this.tracker.update(date ? date.getTime() : null);
        this.video.muted = this.userMuted || view.muted;
        this.onBreak(view);
    }

    setCaptions(enabled: boolean): void {
        this.captions = enabled;
        if (this.hls) this.hls.subtitleDisplay = enabled;
        applyCaptions(this.video, enabled);
    }

    dispose(): void {
        this.disposed = true;
        clearInterval(this.poll);
        clearTimeout(this.retryTimer);
        this.replaced.cancel();
        this.hls?.destroy();
        this.hls = null;
        this.video.pause();
        this.video.removeAttribute('src');
        this.video.load();
        this.onBreak(NO_BREAK);
        this.core.channels.stopWatching();
    }
}
