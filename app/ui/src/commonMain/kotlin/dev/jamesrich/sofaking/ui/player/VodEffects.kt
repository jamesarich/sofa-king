package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withStateAtLeast
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.SkipMode
import dev.jamesrich.sofaking.data.Skips
import dev.jamesrich.sofaking.data.VodMarker
import dev.jamesrich.sofaking.data.VodMarkerKind
import dev.jamesrich.sofaking.data.breakToSkip
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.data.segmentToSkip
import dev.jamesrich.sofaking.resources.retry
import dev.jamesrich.sofaking.ui.LocalLastInput
import dev.jamesrich.sofaking.ui.LocalUptimeMs
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private val log = Logger.withTag("SofaKing")
private const val TICK_MS = 250L
private const val REPORT_MS = 1_000L
private const val TOAST_MS = 3_000L

/**
 * The item on the player: its media session, open only while the app is in front, as the app stops the player behind
 * another app; the volume; the open, again on a retry; each new URL for the item, set on the player once the host is
 * started; the speed, a pending seek, and the player's errors and status.
 */
@Composable
internal fun VodSessionEffects(
    vod: VodSession,
    title: VodTitle,
    open: suspend () -> PlaybackStream,
    stream: PlaybackStream?,
) {
    val player = vod.player
    val state = vod.state
    val volume = LocalPlayerVolume.current
    val currentOpen by rememberUpdatedState(open)
    val currentTitle by rememberUpdatedState(title)
    val lifecycle = appLifecycleOwner.lifecycle
    LifecycleStartEffect(player, lifecycleOwner = appLifecycleOwner) {
        val session = player.openMediaSession { step ->
            vod.seek(state.positionMs + step * if (step < 0) state.steps.backMs else state.steps.forwardMs)
        }
        vod.mediaSession = session
        player.volume = volume.gain
        onStopOrDispose {
            session.close()
            vod.mediaSession = null
        }
    }
    LaunchedEffect(player, volume) { snapshotFlow { volume.gain }.collect { player.volume = it } }
    // The stream the open publishes is loaded by the effect below.
    LaunchedEffect(vod.openRequest) {
        state.failure = null
        vod.onSession("open") { currentOpen() }
    }
    LaunchedEffect(stream?.url) {
        val s = stream ?: return@LaunchedEffect
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) { vod.load(s, currentTitle) }
    }
    LaunchedEffect(vod.speed) { player.setSpeed(vod.speed) }
    val status by player.status.collectAsStateWithLifecycle()
    LaunchedEffect(status.state, vod.pendingSeek) {
        val target = vod.pendingSeek ?: return@LaunchedEffect
        if (status.state == PlaybackState.READY && player.isSeekable) {
            player.seekTo(target)
            vod.pendingSeek = null
        }
    }
    LaunchedEffect(player) {
        player.errors.collect { error -> state.failure = error.code }
    }
    LaunchedEffect(status.state) { state.onStatus(status, opened = stream != null) }
    LaunchedEffect(title, vod.mediaSession) {
        vod.mediaSession?.publish(NowPlaying(title.title, title.subtitle.orEmpty(), title.artworkUrl))
    }
}

/**
 * The item's end: [onStop] gets the end, then the [sleep] timer leaves, or [upNext] names what plays next and the Up
 * next card counts down to it ([onPlayNext]), or [onEnd] runs when nothing does. After [autoplays] says the card has
 * played enough by itself, it asks "Still watching?" and leaves through [onExit] instead.
 */
@Composable
internal fun VodEndingEffects(
    parts: VodParts,
    sleep: SleepTimer,
    autoplays: AutoplayRun,
    upNext: suspend () -> VodUpNext?,
    onStop: (positionMs: Long, durationMs: Long?) -> Unit,
    onEnd: () -> Unit,
    onExit: () -> Unit,
) {
    val state = parts.state
    val ending = parts.ending
    val lastInput = LocalLastInput.current
    val currentOnStop by rememberUpdatedState(onStop)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOnExit by rememberUpdatedState(onExit)
    val currentUpNext by rememberUpdatedState(upNext)
    // Its own effect, so the player's next state change (the app stopping it, say) cannot cancel the lookup.
    LaunchedEffect(state.ended) {
        if (!state.ended) return@LaunchedEffect
        currentOnStop(state.durationMs ?: state.positionMs, state.durationMs)
        if (sleep.atEndOfItem) {
            sleep.cancel()
            currentOnExit()
            return@LaunchedEffect
        }
        val next = currentUpNext()
        ending.stillWatching = next != null && autoplays.askStillWatching(lastInput.atMs)
        state.next = next
        if (next == null) currentOnEnd()
    }
    LaunchedEffect(Unit) {
        ending.upcoming = runCatchingCancellable {
            currentUpNext()
        }.getOrElse { e ->
            log.w { "vod: up next failed: ${e.message}" }
            null
        }
    }
}

/**
 * The Up next card counts down once a second and then plays what it names ([onPlayNext]), or, asking "Still
 * watching?", leaves ([onExit]).
 */
@Composable
internal fun UpNextCountdown(parts: VodParts, autoplays: AutoplayRun, onPlayNext: () -> Unit, onExit: () -> Unit) {
    val state = parts.state
    val ending = parts.ending
    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val currentOnExit by rememberUpdatedState(onExit)
    val lifecycle = appLifecycleOwner.lifecycle
    LaunchedEffect(state.next) {
        if (state.next == null) return@LaunchedEffect
        for (left in (if (ending.stillWatching) STILL_WATCHING_SECONDS else UP_NEXT_SECONDS) downTo 1) {
            ending.secondsLeft = left
            delay(1.seconds)
        }
        if (ending.stillWatching) {
            currentOnExit()
            return@LaunchedEffect
        }
        // Not while another app is in front: the next item opens once this one is back.
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
        autoplays.autoplayed(uptimeMs())
        currentOnPlayNext()
    }
}

/**
 * The clock of the screen: the position in the item, the length, a run into a break, an intro or the credits (each
 * met as [skips] says), a scrub that has settled, and [onPosition] once a second while it plays. The [sleep] timer running out leaves through [onExit].
 */
@Composable
internal fun VodClock(
    parts: VodParts,
    title: VodTitle,
    sleep: SleepTimer,
    skips: Skips,
    onPosition: (positionMs: Long, durationMs: Long?) -> Unit,
    onExit: () -> Unit,
) {
    val vod = parts.vod
    val state = parts.state
    val scrubClock = LocalUptimeMs.current
    val currentTitle by rememberUpdatedState(title)
    val currentSkips by rememberUpdatedState(skips)
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentOnExit by rememberUpdatedState(onExit)
    LaunchedEffect(Unit) {
        var lastReport = 0L
        while (true) {
            delay(TICK_MS)
            if (sleep.due(Clock.System.now().toEpochMilliseconds())) break
            state.settledScrub(scrubClock())?.let(vod::seek)
            val read = vod.tick(currentTitle.markers, currentSkips, parts.toasts)
            val clock = uptimeMs()
            if (read && vod.player.status.value.isPlaying && clock - lastReport >= REPORT_MS) {
                lastReport = clock
                currentOnPosition(state.positionMs, state.durationMs)
            }
        }
        sleep.cancel()
        currentOnExit()
    }
}

/**
 * One tick of the screen's clock: the position in the item, its length, and a run into a break, an intro or the
 * credits, as [markers] place them. False while there is no position to read.
 */
private fun VodSession.tick(markers: List<VodMarker>, skips: Skips, toasts: VodToasts): Boolean {
    val current = stream?.takeIf { player.isSeekable && pendingSeek == null } ?: return false
    val previous = state.positionMs
    val now = player.positionMs + current.offsetMs
    state.positionMs = now
    state.durationMs = current.itemDurationMs ?: player.durationMs?.let { it + current.offsetMs }
    if (state.scrubTarget == null) followMarkers(markers, previous, now, skips, toasts)
    return true
}

private fun VodSession.followMarkers(
    markers: List<VodMarker>,
    previous: Long,
    now: Long,
    skips: Skips,
    toasts: VodToasts,
) {
    breakToSkip(markers, previous, now)?.let { end ->
        when (skips.commercialBreaks) {
            SkipMode.AUTO -> {
                seek(end)
                toasts.breakSkipped = true
            }

            SkipMode.BUTTON -> state.ranIntoBreak()

            SkipMode.OFF -> Unit
        }
    }
    // Offered once playing, so a start inside the intro does not count down while it buffers.
    val segment = segmentToSkip(markers, now, skips)
    if (segment == null) {
        state.cancelAutoSkip()
    } else if (player.status.value.isPlaying) {
        state.inIntro(segment.marker, segment.mode == SkipMode.AUTO)
        if (state.autoSkipDue(uptimeMs())) {
            seek(segment.marker.endMs)
            if (segment.marker.kind == VodMarkerKind.INTRO) toasts.introSkipped = true
        }
    }
}

/** Each toast goes away after [TOAST_MS]. */
@Composable
internal fun VodToastTimers(toasts: VodToasts) {
    LaunchedEffect(toasts.subtitlesFailed) {
        if (!toasts.subtitlesFailed) return@LaunchedEffect
        delay(TOAST_MS)
        toasts.subtitlesFailed = false
    }
    LaunchedEffect(toasts.toast) {
        if (toasts.toast == null) return@LaunchedEffect
        delay(TOAST_MS)
        toasts.toast = null
    }
    LaunchedEffect(toasts.breakSkipped) {
        if (!toasts.breakSkipped) return@LaunchedEffect
        delay(TOAST_MS)
        toasts.breakSkipped = false
    }
    LaunchedEffect(toasts.introSkipped) {
        if (!toasts.introSkipped) return@LaunchedEffect
        delay(TOAST_MS)
        toasts.introSkipped = false
    }
}

/**
 * The bar stays up while [paused] or scrubbing, and leaves a few seconds after the last key, tap or mouse movement
 * otherwise; a button that had focus hands it back to the picture.
 */
@Composable
internal fun VodBarTimer(state: VodPlayerState, paused: Boolean) {
    LaunchedEffect(
        state.overlayVisible,
        state.overlayRequest,
        paused,
        state.scrubTarget,
        state.failure,
        state.autoSkipSeconds != null,
    ) {
        // A scrub, a failure or an intro countdown keeps the bar up.
        val barHeld = state.scrubTarget != null || state.failure != null || state.autoSkipSeconds != null
        if (!state.overlayVisible || paused || barHeld) return@LaunchedEffect
        delay(BAR_IDLE_MS)
        state.overlayVisible = false
    }
}

/**
 * The app stops the player and releases the session while another app is in front (see App); the screen saves where
 * it was through [onStop] and, back in front, reopens the item there, which [VodSessionEffects] loads and seeks. A
 * stream that ended well short of the item opens again where it stopped (see VodPlayerState.onStatus). Leaving
 * releases the item.
 */
@Composable
internal fun VodLifecycleEffects(vod: VodSession, onStop: (positionMs: Long, durationMs: Long?) -> Unit) {
    val state = vod.state
    val currentOnStop by rememberUpdatedState(onStop)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, appLifecycleOwner) {
        if (state.ended || vod.stream == null) return@LifecycleEventEffect
        vod.backgrounded = true
        currentOnStop(state.positionMs, state.durationMs)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START, appLifecycleOwner) {
        if (!vod.backgrounded) return@LifecycleEventEffect
        vod.backgrounded = false
        vod.reopen()
    }
    DisposableEffect(Unit) {
        onDispose { vod.leave(currentOnStop) }
    }
    LaunchedEffect(state.reopenRequest) {
        if (state.reopenRequest == 0) return@LaunchedEffect
        log.w { "vod: stream ended at ${state.positionMs} of ${state.durationMs} ms, reopening" }
        vod.retry()
    }
}

/**
 * The picture takes focus on arrival, and back from a button the bar took away or removed; a skip button takes it as
 * playback reaches what it skips, unless a pointer is in use ([pointerDriven]).
 */
@Composable
internal fun VodFocusEffects(
    state: VodPlayerState,
    focus: PlayerFocus,
    buttons: List<VodButton>,
    pointerDriven: Boolean,
) {
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus.picture) }
    LaunchedEffect(state.overlayVisible, buttons.size) {
        if (state.buttonsFocused && (!state.overlayVisible || buttons.isEmpty())) {
            focus.picture.requestFocus()
        }
    }
    LaunchedEffect(state.focusSkip, state.overlayVisible, buttons.firstOrNull()) {
        if (!state.focusSkip) return@LaunchedEffect
        if (state.overlayVisible && !pointerDriven && buttons.firstOrNull() in SKIP_BUTTONS) {
            // The bar's row is laid out once the frame that shows it is drawn.
            withFrameNanos {}
            focus.buttons.requestFocus()
        }
        // Cleared last: clearing it is a change of this effect's key, which would cancel it before the frame.
        state.focusSkip = false
    }
}
