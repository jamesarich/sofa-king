package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val CAPTION_TOAST_MS = 2_000L

/** How long after the CC key Left and Right keep adjusting the channel's caption timing; each press restarts it. */
internal const val CAPTION_TIMING_WINDOW_MS = 3_000L
private const val MINI_GUIDE_IDLE_MS = 8_000L
private const val STREAM_CARD_IDLE_MS = 15_000L
private const val LIVE_TOAST_MS = 1_500L
private const val SLEEP_CHECK_MS = 1_000L

/**
 * The panels' rest timers and the figures that move while they show: the info bar, the channel list, the stream
 * card and the caption toast each close after a rest, which any use restarts; the stream card's figures and the
 * pause pill's count are read again every second.
 */
@Composable
internal fun PlayerTimers(state: PlayerState, player: LivePlayer) {
    val status by player.status.collectAsStateWithLifecycle()
    val paused = !status.playWhenReady
    LaunchedEffect(state.miniGuideOpen, state.miniGuideActivity) {
        if (!state.miniGuideOpen) return@LaunchedEffect
        delay(MINI_GUIDE_IDLE_MS)
        state.miniGuideOpen = false
    }
    LaunchedEffect(state.streamCardOpen, state.streamCardActivity) {
        if (!state.streamCardOpen) return@LaunchedEffect
        delay(STREAM_CARD_IDLE_MS)
        state.streamCardOpen = false
    }
    // The card's figures move while it is open: the buffer every second, the formats when the engine changes them.
    LaunchedEffect(state.streamCardOpen, player) {
        while (state.streamCardOpen) {
            state.streamInfo = player.streamInfo()
            state.streamBufferedMs = player.bufferedMs
            delay(1.seconds)
        }
    }
    // The pause pill counts how far behind live the picture is; a resume past the live-resume
    // rule says "Live" for a moment.
    LaunchedEffect(paused) {
        if (!paused) {
            val wasPaused = state.pauseState
            state.pauseState = null
            if (wasPaused?.resumesLive == true) {
                state.liveToast = true
                delay(LIVE_TOAST_MS)
                state.liveToast = false
            }
            return@LaunchedEffect
        }
        val pausedAt = uptimeMs()
        while (true) {
            state.pauseState = pauseStatus(pausedAt, uptimeMs())
            delay(1.seconds)
        }
    }
    // The bar stays up while paused, as the library player's does.
    LaunchedEffect(state.overlayVisible, state.overlayRequest, paused) {
        if (!state.overlayVisible || paused) return@LaunchedEffect
        delay(BAR_IDLE_MS)
        state.overlayVisible = false
    }
    LaunchedEffect(state.captionToast) {
        val toast = state.captionToast ?: return@LaunchedEffect
        delay(if (toast.timingMs != null) CAPTION_TIMING_WINDOW_MS else CAPTION_TOAST_MS)
        state.captionToast = null
    }
}

/** The sleep timer: once it runs out, [onExit] leaves the player as Back does. */
@Composable
internal fun SleepExit(sleep: SleepTimer, onExit: () -> Unit) {
    val currentOnExit by rememberUpdatedState(onExit)
    LaunchedEffect(sleep) {
        while (true) {
            delay(SLEEP_CHECK_MS)
            if (sleep.due(Clock.System.now().toEpochMilliseconds())) {
                sleep.cancel()
                currentOnExit()
                break
            }
        }
    }
}

/** A new info bar opened by a key carries the key legend for the first few bars; [onKeyLegendShow] saves the count. */
@Composable
internal fun KeyLegendEffect(state: PlayerState, pointerDriven: Boolean, onKeyLegendShow: (Int) -> Unit) {
    val currentOnKeyLegendShow by rememberUpdatedState(onKeyLegendShow)
    LaunchedEffect(state.overlayVisible) {
        if (!state.overlayVisible || pointerDriven) return@LaunchedEffect
        state.legendForNewBar()?.let(currentOnKeyLegendShow)
    }
}
