package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withStateAtLeast
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.reconnectDelayMs
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.secondsUp
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How long the player waits for the lineup it asked for to catch up with the one it holds. */
private const val LINEUP_SYNC_MS = 2_000L

/**
 * Reconnects with backoff for as long as the channel is on screen, and only while the app is in front: a retry in the
 * background would take the TV's decoder and a session on the server. A 410 skips the first wait: the server asked
 * for the session to be re-read, which is what tuning again ([load]) does. A channel id the server no longer has is
 * followed into the reloaded lineup ([reloadLineup]); with nothing to follow, [onChannelMissing] takes over.
 */
@Composable
internal fun ReconnectEffect(
    state: PlayerState,
    reloadLineup: suspend () -> Lineup?,
    onChannelMissing: (Channel) -> Unit,
    load: (Channel) -> Unit,
) {
    val currentReloadLineup by rememberUpdatedState(reloadLineup)
    val currentOnChannelMissing by rememberUpdatedState(onChannelMissing)
    val currentLoad by rememberUpdatedState(load)
    val lifecycle = appLifecycleOwner.lifecycle
    LaunchedEffect(state.failure) {
        val current = state.failure ?: run {
            state.recovering = null
            return@LaunchedEffect
        }
        if (current.code == CHANNEL_NOT_FOUND) {
            followNewIds(state, current.attempt, lifecycle, { currentReloadLineup() }) { currentOnChannelMissing(it) }
            return@LaunchedEffect
        }
        if (!(current.immediate && current.attempt == 0)) countDown(state, reconnectDelayMs(current.attempt))
        state.retryIn = 0
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
        val channel = state.currentChannel ?: return@LaunchedEffect
        state.failure = current.copy(attempt = current.attempt + 1)
        currentLoad(channel)
    }
    LaunchedEffect(state.failure == null) { if (state.failure == null) state.retryIn = null }
}

/**
 * The server's lineup has new ids (a re-import): retrying this one would fail forever. Reloads the lineup and follows
 * the channel by number, then name; with nothing to follow, the guide takes over through [onChannelMissing].
 */
private suspend fun followNewIds(
    state: PlayerState,
    attempt: Int,
    lifecycle: Lifecycle,
    reload: suspend () -> Lineup?,
    onChannelMissing: (Channel) -> Unit,
) {
    val channel = state.currentChannel ?: return
    state.recovering = channel
    // The replacement is looked for in the whole lineup; the ring's ids are the ones that went stale.
    if (!state.widened) {
        state.widened = true
        state.index = state.lineupChannels.indexOfFirst { it.guideId == channel.guideId }.coerceAtLeast(0)
    }
    val reloaded = reloadWithBackoff(attempt, lifecycle, reload)
    withTimeoutOrNull(LINEUP_SYNC_MS) { snapshotFlow { state.channels }.first { it == reloaded.channels } }
    val target = resolveReplacement(state.channels, channel)
    if (target == null) {
        onChannelMissing(channel)
    } else {
        state.index = target
        state.clearFailure()
    }
}

/** Unreachable or timed out: the reload, not the dead id, is what backs off and tries again, while in front. */
private suspend fun reloadWithBackoff(firstAttempt: Int, lifecycle: Lifecycle, reload: suspend () -> Lineup?): Lineup {
    var attempt = firstAttempt
    var reloaded = reload()
    while (reloaded == null) {
        delay(reconnectDelayMs(attempt++))
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
        reloaded = reload()
    }
    return reloaded
}

/** Waits [waitMs]; the failure card counts it down a second at a time. */
private suspend fun countDown(state: PlayerState, waitMs: Long) {
    val until = uptimeMs() + waitMs
    while (true) {
        val remaining = until - uptimeMs()
        if (remaining <= 0L) break
        state.retryIn = secondsUp(remaining)
        delay(minOf(remaining.milliseconds, 1.seconds))
    }
}
