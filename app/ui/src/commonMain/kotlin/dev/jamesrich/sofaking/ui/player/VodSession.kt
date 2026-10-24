package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.PlaybackStream
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.resources.retry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

private val log = Logger.withTag("SofaKing")

/**
 * One item's playback on the shared player: the streams the session hands this page for [itemId], where the item is,
 * and the moves on it (a seek, a retry, a reopen). Up next composes the next page beside this one while they
 * cross-fade, so each plays, seeks and releases only its own item's session.
 */
@Stable
internal class VodSession(
    val state: VodPlayerState,
    val player: LivePlayer,
    private val sessions: PlaybackSessions,
    private val itemId: String,
    private val scope: CoroutineScope,
) {
    /** The URL this page last set on the player; null until its first stream. */
    var loadedUrl by mutableStateOf<String?>(null)
        private set

    /** Bumped to open the item again, as a retry with no stream does. */
    var openRequest by mutableIntStateOf(0)
        private set

    /** The stream position to seek to once the item at a new URL is ready: a file played as it is starts at 0. */
    var pendingSeek by mutableStateOf<Long?>(null)

    /** Each item starts at normal speed; the Speed button steps it on, and leaving puts the shared player back. */
    var speed by mutableFloatStateOf(1f)

    var mediaSession by mutableStateOf<MediaSessionHandle?>(null)

    /** Whether the app stopped while this page played, so its return reopens the item. */
    var backgrounded by mutableStateOf(false)

    /** The stream the session holds for this page's item, if any. */
    val stream: PlaybackStream? get() = sessions.current.value?.takeIf { it.itemId == itemId }

    /** Runs [what] on the session, waiting out the converter; a failure shows on the failure card. */
    private fun launchOnSession(what: String, open: suspend () -> Any?) {
        scope.launch { onSession(what, open) }
    }

    /** As [launchOnSession], in the caller's coroutine. */
    suspend fun onSession(what: String, open: suspend () -> Any?) {
        runCatchingCancellable {
            state.waitForConverter { open() }
        }.onFailure { e ->
            log.w { "vod: $what failed: ${e.message}" }
            state.failure = failureCode(e)
        }
    }

    /** Stops the stream the player holds, and asks the session for a new one through [open]. */
    fun restream(what: String, open: suspend () -> Any?) {
        player.stop()
        launchOnSession(what, open)
    }

    /** Moves to [targetMs] in the item: inside the stream the player holds, or a conversion restarted there. */
    fun seek(targetMs: Long) {
        val current = stream ?: return
        when (val plan = vodSeekPlan(current, targetMs, player.durationMs, current.itemDurationMs)) {
            is VodSeekPlan.InStream -> player.seekTo(plan.streamMs)
            is VodSeekPlan.Restart -> restream("restart") { sessions.restartAt(plan.itemMs) }
        }
        state.positionMs = targetMs
    }

    fun togglePause() {
        if (player.playWhenReady) player.pause() else player.play()
    }

    /** Plays the item again after a failure: a file played as it is picks up where it failed; a conversion restarts. */
    fun retry() {
        state.failure = null
        player.stop()
        val current = stream
        if (current?.mode == Wire.VodMode.DIRECT) {
            pendingSeek = (state.positionMs - current.offsetMs).takeIf { it > 0 }
            player.prepare()
        } else if (current != null) {
            seek(state.positionMs)
            if (player.currentStreamUrl == current.url) player.prepare()
        } else {
            openRequest++
        }
    }

    /**
     * Sets a new URL for the item (the first open, a restart, a reopen) on the player, playing as [title]; a file
     * played as it is then seeks to where it was asked to start. A new URL for this page's item keeps it paused; a
     * page's first item plays, even after another item was left paused.
     */
    fun load(s: PlaybackStream, title: VodTitle) {
        if (player.currentStreamUrl == s.url) return
        val wasPaused =
            loadedUrl != null && !player.playWhenReady && player.hasItem && player.currentChannelId.isNullOrEmpty()
        player.stop()
        player.setStream(s, NowPlaying(title.title, title.subtitle.orEmpty(), title.artworkUrl))
        loadedUrl = s.url
        pendingSeek = (s.startAtMs - s.offsetMs).takeIf { it > 0 }
        state.positionMs = s.startAtMs
        state.durationMs = s.itemDurationMs
        player.playWhenReady = !wasPaused
        player.prepare()
    }

    /** The app is back in front after stopping this page: the item reopens where it was, or opens again. */
    fun reopen() {
        scope.launch {
            var reopened = true
            onSession("reopen") { reopened = sessions.reopen() != null }
            if (!reopened) openRequest++
        }
    }

    /**
     * Leaves the item: the speed goes back, [onStop] gets where it stopped unless it ended, and the player stops only
     * on this page's stream, or on nothing yet while no other item holds the session.
     */
    fun leave(onStop: (positionMs: Long, durationMs: Long?) -> Unit) {
        player.setSpeed(1f)
        if (!state.ended) onStop(state.positionMs, state.durationMs)
        val other = sessions.current.value?.itemId?.let { it != itemId } == true
        if (if (loadedUrl == null) !other else player.currentStreamUrl == loadedUrl) player.stop()
        sessions.releaseAsync(forget = true, itemId = itemId)
    }
}

@Composable
internal fun rememberVodSession(player: LivePlayer, sessions: PlaybackSessions, itemId: String): VodSession {
    val scope = rememberCoroutineScope()
    return remember(player, sessions, itemId, scope) { VodSession(VodPlayerState(), player, sessions, itemId, scope) }
}

/** A CC or Zoom press's answer, [text] with [arg] in it if any; compared by identity so a repeat press restarts it. */
internal class VodToast(val text: StringResource, val arg: StringResource? = null)

/** The short notes over the picture, each shown for a moment: a skipped break or intro, failed subtitles, CC, Zoom. */
@Stable
internal class VodToasts {
    var breakSkipped by mutableStateOf(false)
    var introSkipped by mutableStateOf(false)
    var subtitlesFailed by mutableStateOf(false)
    var toast by mutableStateOf<VodToast?>(null)
}
