package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val log = Logger.withTag("SofaKing")

/** How long a failed multiview waits before it asks the server again. */
internal const val MULTIVIEW_RETRY_MS = 5_000L

/**
 * A multiview on [player]: opened on the server through [sessions], played, and opened again [MULTIVIEW_RETRY_MS]
 * after a failure. [audio] is the tile whose sound plays, moved at once on a press while the server catches up.
 */
@Stable
internal class MultiviewPlay(
    private val player: LivePlayer,
    private val sessions: PlaybackSessions,
    private val scope: CoroutineScope,
) {
    var audio by mutableIntStateOf(0)
        private set

    /** Why the multiview is not playing: a server error code, or null while it plays or opens. */
    var failure by mutableStateOf<String?>(null)
        private set

    /** True from a start until the first stream is on the player. */
    var opening by mutableStateOf(false)
        private set

    /** The channels playing, or being opened; empty before the first start. */
    var channelIds: List<String> = emptyList()
        private set
    private var job: Job? = null

    /** Plays [ids] with the sound of tile [tile], in place of whatever plays. */
    fun start(ids: List<String>, tile: Int = 0) {
        channelIds = ids
        audio = tile
        open()
    }

    /** Moves the sound to [tile]; the server starts its stream again, which the player then loads. */
    fun moveAudio(tile: Int) {
        if (tile == audio || channelIds.isEmpty()) return
        audio = tile
        if (failure != null || opening) {
            open()
            return
        }
        job?.cancel()
        job = scope.launch {
            runCatchingCancellable { sessions.moveAudio(tile) }
                .onSuccess { stream -> stream?.let(::play) ?: open() }
                .onFailure { e ->
                    log.w(e) { "multiview: moving the sound failed" }
                    fail(failureCode(e))
                }
        }
    }

    /** The player failed on the stream: the multiview opens again after a pause. */
    fun playerFailed(code: String) {
        if (channelIds.isNotEmpty()) fail(code)
    }

    private fun open() {
        job?.cancel()
        failure = null
        opening = true
        job = scope.launch {
            runCatchingCancellable { sessions.multiview(channelIds, audio) }
                .onSuccess(::play)
                .onFailure { e ->
                    log.w(e) { "multiview: open failed" }
                    fail(failureCode(e))
                }
        }
    }

    private fun play(stream: PlaybackStream) {
        player.stop()
        player.setStream(stream)
        player.playWhenReady = true
        player.prepare()
        failure = null
        opening = false
    }

    private fun fail(code: String) {
        failure = code
        opening = false
        job?.cancel()
        job = scope.launch {
            delay(MULTIVIEW_RETRY_MS)
            open()
        }
    }
}

@Composable
internal fun rememberMultiviewPlay(player: LivePlayer, sessions: PlaybackSessions): MultiviewPlay {
    val scope = rememberCoroutineScope()
    return remember(player, sessions, scope) { MultiviewPlay(player, sessions, scope) }
}
