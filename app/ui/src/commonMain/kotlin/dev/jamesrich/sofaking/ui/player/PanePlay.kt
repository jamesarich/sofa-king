package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.reconnectDelayMs
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val log = Logger.withTag("SofaKing")

/** The session one pane of multiview holds. */
internal interface PaneSessions {
    /** Streams the server replaced under the held session, which the player must reopen. */
    val replaced: Flow<PlaybackStream>

    /** The stream for [channelId], to watch: the held session re-read when it is this channel's, else a new one. */
    suspend fun tune(channelId: String): PlaybackStream

    /** Releases the held session, with nothing kept to reopen. */
    suspend fun release()
}

/** [PaneSessions] on [sessions], one of the connection's pane sessions. */
internal fun paneSessions(sessions: PlaybackSessions): PaneSessions = object : PaneSessions {
    override val replaced: Flow<PlaybackStream> get() = sessions.replaced

    override suspend fun tune(channelId: String): PlaybackStream = sessions.tune(channelId, PlaybackIntent.WATCH)

    override suspend fun release() = sessions.release(forget = true)
}

/**
 * One pane of multiview: a channel on a [player] and a session of its own, so nothing another pane does touches its
 * stream. A stream that fails or ends is opened again with the live player's backoff, re-reading the session first;
 * a channel the server no longer has is left failed.
 */
@Stable
internal class PanePlay(private val player: LivePlayer, private val sessions: PaneSessions) {
    /** Why the pane is not playing: a player or server error code, or null while it plays or opens. */
    var failure by mutableStateOf<String?>(null)
        private set

    /** True from a tune until its picture plays or it fails. */
    var opening by mutableStateOf(false)
        private set

    /** One channel at a time: the next waits for the last one's session to be released. */
    private val gate = Mutex()

    /** Plays [channelId] until canceled, then stops the player and releases the session. */
    suspend fun play(channelId: String): Unit = gate.withLock {
        try {
            coroutineScope {
                val trouble = Channel<PlayerError>(Channel.CONFLATED)
                var attempt = 0
                launch(start = CoroutineStart.UNDISPATCHED) { player.errors.collect { trouble.trySend(it) } }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    player.status.collect { status ->
                        when {
                            status.state == PlaybackState.ENDED -> trouble.trySend(PlayerError(STREAM_ENDED))

                            status.isPlaying -> {
                                attempt = 0
                                failure = null
                                opening = false
                            }
                        }
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    sessions.replaced.collect { stream ->
                        if (stream.channelId == channelId && player.currentStreamUrl != stream.url) {
                            player.replaceStream(stream)
                            player.prepare()
                        }
                    }
                }
                var reread = false
                while (true) {
                    opening = failure == null
                    player.stop()
                    trouble.tryReceive()
                    val failed = runCatchingCancellable { sessions.tune(channelId) }
                        .onSuccess { stream ->
                            player.setStream(stream)
                            player.playWhenReady = true
                            player.prepare()
                        }
                        .exceptionOrNull()
                    val error = failed?.let { PlayerError(failureCode(it)) } ?: trouble.receive()
                    log.w { "multiview: pane $channelId failed ${error.code}" }
                    failure = error.code
                    opening = false
                    if (error.code == Wire.ErrorCode.CHANNEL_NOT_FOUND) awaitCancellation()
                    // A 410 from the relay re-reads the session at once, the first time.
                    val now = error.streamGone && !reread
                    reread = error.streamGone
                    if (!now) delay(reconnectDelayMs(attempt++))
                }
            }
        } finally {
            withContext(NonCancellable) {
                player.stop()
                sessions.release()
                failure = null
                opening = false
            }
        }
    }
}
