import type { WebCue, WebStream, WebTracks } from '@sofaking/core';
import Hls from 'hls.js';
import { button, h } from './dom';
import { setKeyHandler } from './focus';
import { sendRelease, toggleFullScreen } from './liveView';
import { startPlaying } from './media';
import { StatusLine } from './status';
import { audioRows, cueTimes, drawnIn, offersTracks, subtitleRows, toggledSubtitle, type TrackRow } from './tracks';
import type { Context, View } from './view';

const SAVE_EVERY_MS = 10_000;
const SEEK_STEP_S = 10;
const PRESS_TO_PLAY = 'Press Enter to play';
/** Opens in a row that fail before the picture plays again, after which the page stops and says so. */
const MAX_DROPS = 8;
const NO_SUBTITLES = 'These subtitles did not load.';

/**
 * A library item or recording: the server's HLS through hls.js (or the browser's own where it has no MediaSource), or
 * the file as it is in the `<video>`. Progress goes to core, which saves it; a seek past what a conversion has written
 * restarts the conversion there. Audio and subtitles lists the file's tracks: another audio track, or a picture
 * subtitle the server has to draw in, plays the item again from where it is, and a text subtitle is drawn by the
 * `<video>` from the cues core reads.
 */
export function vodView(ctx: Context, itemId: string, fromStart: boolean): View {
    const video = h('video', { attrs: { playsinline: true, controls: true, autoplay: true, 'aria-label': 'Picture' } });
    const statusEl = h('p', { class: 'player-status', attrs: { role: 'status' } });
    const status = new StatusLine(statusEl);
    const tracksButton = button('Audio and subtitles', () => showPanel(!panelOpen));
    tracksButton.hidden = true;
    tracksButton.setAttribute('aria-expanded', 'false');
    const panel = h('div', { class: 'track-panel', attrs: { role: 'group', 'aria-label': 'Audio and subtitles' } });
    panel.hidden = true;
    const el = h(
        'section',
        { class: 'player vod' },
        video,
        statusEl,
        panel,
        h(
            'div',
            { class: 'controls' },
            button('Back', ctx.back),
            button('Back 10 s', () => seekBy(-SEEK_STEP_S)),
            button('Forward 10 s', () => seekBy(SEEK_STEP_S)),
            tracksButton,
            button('Full screen', () => toggleFullScreen(el)),
        ),
    );

    let stream: WebStream | null = null;
    let hls: Hls | null = null;
    let disposed = false;
    let waited = 0;
    let lastSaved = 0;
    let dropped = 0;
    /** The place in the item the picture last was, or was told to start at: where a reopen resumes. */
    let positionMs = 0;
    let reopenTimer: ReturnType<typeof setTimeout> | undefined;

    /** The file's tracks, read before it first opens; null while unread, or when the server did not give them. */
    let tracks: WebTracks | null = null;
    /** The audio track on in the list, and the one asked of the stream, null for the file's own. */
    let audioOn: number | null = null;
    let audioAsked: number | null = null;
    /** The subtitles on, and the ones C turns back on. */
    let subtitle: number | null = null;
    let lastSubtitle: number | null = null;
    let cues: WebCue[] = [];
    let panelOpen = false;
    const text = video.addTextTrack('subtitles', 'Subtitles');

    /** Where the item is now, in milliseconds. */
    const itemMs = () => (stream?.offsetMs ?? 0) + video.currentTime * 1000;
    const durationMs = () => stream?.itemDurationMs ?? (Number.isFinite(video.duration) ? video.duration * 1000 : 0);
    const writtenMs = () => (video.seekable.length > 0 ? video.seekable.end(video.seekable.length - 1) * 1000 : null);

    function load(next: WebStream, startSeconds: number): void {
        stream = next;
        hls?.destroy();
        hls = null;
        const start = Math.max(0, startSeconds);
        positionMs = next.offsetMs + start * 1000;
        drawCues();
        if (next.mode !== 'direct' && Hls.isSupported()) {
            const player = new Hls({ startPosition: start > 0 ? start : -1 });
            player.on(Hls.Events.ERROR, (_event, data) => {
                if (data.fatal) reopen();
            });
            hls = player;
            player.loadSource(next.url);
            player.attachMedia(video);
        } else {
            video.src = next.url;
            if (start > 0) video.addEventListener('loadedmetadata', () => (video.currentTime = start), { once: true });
        }
        video.play().catch(() => status.set(PRESS_TO_PLAY));
    }

    /** Opens the session; [resumeMs] is the place in the item to carry on from after a drop, else where the item says. */
    async function open(resumeMs: number | null = null): Promise<void> {
        try {
            if (tracks === null) await readTracks();
            if (disposed) return;
            const drawn = drawnIn(tracks, subtitle);
            const next = await ctx.core.item.play(itemId, resumeMs === null && fromStart, audioAsked, drawn);
            if (disposed) return;
            waited = 0;
            status.set('');
            load(next, ((resumeMs ?? next.startAtMs) - next.offsetMs) / 1000);
        } catch (error) {
            if (disposed) return;
            const delay = ctx.core.decisions.isConverterBusy(error) ? (ctx.core.decisions.converterRetryDelayMs(waited) ?? null) : null;
            const terminal = ctx.core.decisions.terminalOpenFailure(error);
            if (terminal) {
                status.set(terminal);
                return;
            }
            if (delay === null) {
                if (resumeMs === null) status.set('This item did not open.');
                else reopen();
                return;
            }
            status.set('Every converter is busy; trying again');
            waited += delay;
            reopenTimer = setTimeout(() => {
                reopenTimer = undefined;
                void open(resumeMs);
            }, delay);
        }
    }

    /** A dropped session is read again through the server and resumed where the picture was, never retried as it was. */
    function reopen(): void {
        if (disposed || reopenTimer !== undefined) return;
        if (dropped >= MAX_DROPS) {
            status.set('This item stopped playing.');
            return;
        }
        status.set('Reconnecting');
        const resumeMs = positionMs;
        reopenTimer = setTimeout(() => {
            reopenTimer = undefined;
            void open(resumeMs);
        }, ctx.core.decisions.reconnectDelayMs(dropped++));
    }

    // The server replaced the stream under the held session: load the new one at the same place.
    const replaced = ctx.core.channels.watchReplaced((next) => {
        if (!disposed) load(next, (itemMs() - next.offsetMs) / 1000);
    });

    /** The file's tracks and the ones it starts with; the player opens without the list when they do not come. */
    async function readTracks(): Promise<void> {
        const read = (await ctx.core.item.tracks(itemId).catch(() => null)) ?? null;
        if (disposed || read === null) return;
        tracks = read;
        audioOn = read.audioIndex ?? null;
        audioAsked = read.openAudio ?? null;
        subtitle = read.subtitleIndex ?? null;
        lastSubtitle = subtitle;
        tracksButton.hidden = !offersTracks(read);
        drawPanel();
        void readCues();
    }

    /** Puts the cues on the `<video>` in the stream's own time, and shows them while subtitles are on. */
    function drawCues(): void {
        // A disabled track has no cue list to change.
        text.mode = 'hidden';
        for (const cue of Array.from(text.cues ?? [])) text.removeCue(cue);
        for (const cue of cueTimes(cues, stream?.offsetMs ?? 0)) text.addCue(new VTTCue(cue.start, cue.end, cue.text));
        text.mode = cues.length > 0 ? 'showing' : 'disabled';
    }

    /** Reads the text of the subtitles that are on; a picture track's are already in the picture. */
    async function readCues(): Promise<void> {
        const index = subtitle;
        status.clearIf(NO_SUBTITLES);
        if (index === null || drawnIn(tracks, index) !== null) {
            cues = [];
            drawCues();
            return;
        }
        const read = (await ctx.core.item.subtitles(itemId, index).catch(() => null)) ?? null;
        if (disposed || subtitle !== index) return;
        if (read === null) status.set(NO_SUBTITLES);
        cues = read ?? [];
        drawCues();
    }

    /** Plays the item again where it is with the tracks now picked. */
    async function switchTracks(): Promise<void> {
        const at = itemMs();
        try {
            const next = await ctx.core.item.switchTracks(itemId, at, audioAsked, drawnIn(tracks, subtitle));
            if (!disposed) load(next, (at - next.offsetMs) / 1000);
        } catch {
            if (!disposed) reopen();
        }
    }

    function pickAudio(index: number): void {
        if (index === audioOn) return;
        audioOn = index;
        audioAsked = index;
        ctx.core.item.picked(itemId, audioOn, subtitle);
        drawPanel();
        void switchTracks();
    }

    function pickSubtitle(index: number | null): void {
        if (index === subtitle) return;
        const drawn = drawnIn(tracks, subtitle);
        subtitle = index;
        if (index !== null) lastSubtitle = index;
        ctx.core.item.picked(itemId, audioOn, subtitle);
        drawPanel();
        if (drawn !== drawnIn(tracks, index)) void switchTracks();
        void readCues();
    }

    function rows(title: string, list: TrackRow[], pick: (index: number | null) => void): HTMLElement | null {
        if (list.length === 0) return null;
        return h(
            'div',
            { class: 'track-group' },
            h('h2', { text: title }),
            ...list.map((row) =>
                h('button', {
                    class: row.on ? 'track on' : 'track',
                    text: row.label,
                    attrs: { type: 'button', 'aria-pressed': row.on ? 'true' : 'false', 'data-key': `${title}:${row.index ?? 'off'}` },
                    on: { click: () => pick(row.index) },
                }),
            ),
        );
    }

    function drawPanel(): void {
        if (!tracks) return;
        const focused = (document.activeElement as HTMLElement | null)?.dataset?.key;
        panel.replaceChildren(
            ...[
                rows('Audio', audioRows(tracks, audioOn), (index) => index !== null && pickAudio(index)),
                rows('Subtitles', subtitleRows(tracks, subtitle), pickSubtitle),
            ].filter((group): group is HTMLElement => group !== null),
        );
        if (focused && panelOpen) panel.querySelector<HTMLElement>(`[data-key="${CSS.escape(focused)}"]`)?.focus();
    }

    function showPanel(open: boolean): void {
        panelOpen = open && offersTracks(tracks);
        panel.hidden = !panelOpen;
        tracksButton.setAttribute('aria-expanded', panelOpen ? 'true' : 'false');
        if (panelOpen) (panel.querySelector<HTMLElement>('.track.on') ?? panel.querySelector<HTMLElement>('.track'))?.focus();
        else if (panel.contains(document.activeElement) || document.activeElement === document.body) tracksButton.focus();
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
        dropped = 0;
        if (video.currentTime > 0) positionMs = itemMs();
        status.clearIf(PRESS_TO_PLAY);
        status.clearIf('Reconnecting');
        const now = Date.now();
        if (now - lastSaved < SAVE_EVERY_MS) return;
        lastSaved = now;
        ctx.core.item.progress(itemId, itemMs(), durationMs());
    });
    video.addEventListener('ended', () => ctx.back());
    video.addEventListener('error', () => {
        if (!disposed && !hls) reopen();
    });

    void open();
    video.focus();
    setKeyHandler((event) => {
        if (event.target instanceof HTMLButtonElement && event.key === ' ') return false;
        if (panelOpen && (event.key === 'Escape' || event.key === 'Backspace')) {
            showPanel(false);
            return true;
        }
        // In the list the arrows move between its tracks rather than seek.
        if (panelOpen && panel.contains(document.activeElement)) return false;
        switch (event.key) {
            case 'c':
            case 'C':
                if (!tracks || tracks.subtitles.length === 0) return false;
                pickSubtitle(toggledSubtitle(tracks, subtitle, lastSubtitle));
                return true;
            case ' ':
                if (video.paused) startPlaying(video, () => status.set(PRESS_TO_PLAY));
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
            clearTimeout(reopenTimer);
            replaced.cancel();
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
