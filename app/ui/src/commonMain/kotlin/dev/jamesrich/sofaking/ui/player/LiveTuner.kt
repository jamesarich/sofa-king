package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.withStateAtLeast
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Instant

private val log = Logger.withTag("SofaKing")

/**
 * Tunes [player] to a channel, one tune at a time: a zap cancels the previous channel's session request before it
 * lands on the player. It also holds the media session while one is open.
 */
@Stable
internal class LiveTuner(
    private val player: LivePlayer,
    private val sessions: PlaybackSessions,
    private val state: PlayerState,
    private val scope: CoroutineScope,
    private val lifecycle: Lifecycle,
) {
    /**
     * Opened and closed by [LiveSessionEffects], never during composition. The system's next, previous, play and
     * pause (MediaSession, media keys) reach the player through it.
     */
    var mediaSession by mutableStateOf<MediaSessionHandle?>(null)

    private var job: Job? = null

    /** Plays [channel], published to the media session as [nowPlaying]. */
    fun load(channel: Channel, nowPlaying: NowPlaying) {
        mediaSession?.publish(nowPlaying)
        job?.cancel()
        val captionSync = player.captionSync
        // Handed over from the guide preview mid-stream: keep it, and let the caption lead grow back from zero.
        // The same session is promoted to watch, so the stream continues and its lease grows to 90 s.
        val playerState = player.status.value.state
        if (player.currentChannelId == channel.guideId &&
            (playerState == PlaybackState.READY || playerState == PlaybackState.BUFFERING)
        ) {
            captionSync.effectiveUs = 0L
            job = scope.launch { promote(channel) }
            return
        }
        // Reset the renderers so the next stream's decoder rescales instead of reusing the old surface size,
        // and before the session changes hands, so the released stream's end is not read as a failure.
        player.stop()
        // Playback can't start until the full advance is buffered, so a fresh tune starts at the full lead.
        captionSync.effectiveUs = captionSync.advanceUs
        job = scope.launch { tune(channel, nowPlaying) }
    }

    private suspend fun promote(channel: Channel) {
        runCatchingCancellable {
            sessions.setIntent(channel.guideId, PlaybackIntent.WATCH)
        }.onFailure { e ->
            log.w(e) { "session: promote failed" }
        }
    }

    private suspend fun tune(channel: Channel, nowPlaying: NowPlaying) {
        while (true) {
            val stream = runCatchingCancellable {
                sessions.tune(channel.guideId, PlaybackIntent.WATCH)
            }.getOrElse { e ->
                // The reconnect loop tunes again with backoff, as it does for a stream that failed.
                state.fail(failureCode(e))
                return
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                player.setStream(stream, nowPlaying)
                player.prepare()
                return
            }
            // The app went behind another one while the session opened, and its stop released that session.
            lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
        }
    }
}

@Composable
internal fun rememberLiveTuner(player: LivePlayer, sessions: PlaybackSessions, state: PlayerState): LiveTuner {
    val scope = rememberCoroutineScope()
    val lifecycle = appLifecycleOwner.lifecycle
    return remember(player, sessions, state, scope, lifecycle) { LiveTuner(player, sessions, state, scope, lifecycle) }
}

/** What the media session and the system show for [channel] at [now]: the program on, else the channel's name. */
internal fun nowPlayingFor(lineup: Lineup, channel: Channel, now: Instant) = NowPlaying(
    title = lineup.nowAndNext(channel, now).first?.title ?: channel.name,
    subtitle = channel.label(),
    artworkUrl = channel.logo,
)

/**
 * The player's ties to the system: the media session, open only while the app is in front, as the app stops the
 * player behind another app; the volume, silent while [muted]; and the player's status and errors.
 */
@Composable
internal fun LiveSessionEffects(player: LivePlayer, state: PlayerState, tuner: LiveTuner, muted: Boolean) {
    val volume = LocalPlayerVolume.current
    val currentMuted by rememberUpdatedState(muted)
    val gain = { if (currentMuted) 0f else volume.gain }
    LifecycleStartEffect(player, lifecycleOwner = appLifecycleOwner) {
        val session = player.openMediaSession { step -> state.zapStep(step) }
        tuner.mediaSession = session
        player.volume = gain()
        onStopOrDispose {
            session.close()
            tuner.mediaSession = null
        }
    }
    LaunchedEffect(player, volume) { snapshotFlow { gain() }.collect { player.volume = it } }
    LaunchedEffect(player) { player.status.collect { state.onStatus(it) } }
    LaunchedEffect(player) {
        player.errors.collect { error -> state.fail(error.code, immediate = error.streamGone) }
    }
}

/**
 * Tunes the channel on screen once a zap has settled, while the app is in front, and moves the media session's
 * program title on with the minute clock when the show changes; the stream is not touched. The lineup refreshes
 * every few hours and may reorder or grow, and the [ring] widens from the guide's filter to the whole lineup; the
 * tuned channel keeps its stream and its row follows it.
 */
@Composable
internal fun TuneEffects(
    state: PlayerState,
    tuner: LiveTuner,
    ring: List<Channel>,
    lineup: Lineup,
    now: Instant,
    onChannelChange: (Channel) -> Unit,
) {
    val currentOnChannelChange by rememberUpdatedState(onChannelChange)
    val lifecycle = appLifecycleOwner.lifecycle
    LaunchedEffect(ring) { state.followRing(ring) }
    val currentChannel = state.currentChannel
    LaunchedEffect(state.index, currentChannel?.guideId, lifecycle, state.recovering) {
        // While a dead id is being followed, nothing tunes until the row for its replacement is known.
        if (state.recovering != null) return@LaunchedEffect
        val channel = currentChannel ?: return@LaunchedEffect
        // Channels passed under quick presses are never tuned; the info bar follows each press at once.
        delay(zapSettleMs(state.tuned, channel))
        lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
            val before = state.tuned
            state.tuned = channel
            // A refresh that keeps the channel preserves its stream and session.
            if (before?.guideId != channel.guideId) {
                tuner.load(channel, nowPlayingFor(lineup, channel, now))
                currentOnChannelChange(channel)
            }
        }
    }
    LaunchedEffect(state.index, now, tuner.mediaSession) {
        val channel = state.currentChannel ?: return@LaunchedEffect
        tuner.mediaSession?.publish(nowPlayingFor(lineup, channel, now))
    }
}
