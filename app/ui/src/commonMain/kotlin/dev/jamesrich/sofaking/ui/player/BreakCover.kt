package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.AdBreakNow
import dev.jamesrich.sofaking.data.BreakWatch
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.runCatchingCancellable
import dev.jamesrich.sofaking.ui.isOk
import kotlinx.coroutines.delay

/** How often the player looks for an ad in its own playlist; a break is several segments of a few seconds each. */
private const val AD_BREAK_POLL_MS = 500L

/**
 * How often an engine that cannot read the playlist asks the server instead, and how long it waits after a failed ask.
 */
private const val AD_BREAK_ASK_MS = 1_000L
private const val AD_BREAK_RETRY_MS = 15_000L
private val log = Logger.withTag("SofaKing")

/**
 * Whether the break screen is up over a marked ad break, and how long the break has left. The server marks the ad
 * segments of a streaming channel's playlist; while Settings asks, the break screen covers them. Media3 reads the
 * marks itself; the other engines ask the server, which knows the segments they fetched. OK or Back shows the picture
 * again until that break is over ([BreakWatch]).
 */
@Stable
internal class BreakCover {
    var inBreak by mutableStateOf(false)
        private set
    var leftMs by mutableStateOf<Long?>(null)
        private set

    /** OK or Back put the screen away for this break. */
    var dismissed by mutableStateOf(false)

    /** Whether the break screen covers the picture. */
    val up: Boolean get() = inBreak && !dismissed

    /** A new channel, or the setting changed: whatever was covered is over. */
    fun reset() {
        inBreak = false
        dismissed = false
    }

    /** Follows [player]'s breaks for as long as it runs. */
    suspend fun watch(player: LivePlayer, sessions: PlaybackSessions): Nothing {
        val watch = BreakWatch()
        var lastFound: AdBreakNow? = null
        while (true) {
            val reading = readBreak(player, sessions)
            val shown = if (reading.stalled) watch.stalled() else watch.shown(reading.found)
            if (reading.found == null && shown != null && lastFound != null) {
                log.d {
                    "ad break: ${if (reading.stalled) "picture stalled" else "reading lost it"}, holding " +
                        "${shown.remainingMs ?: "?"} ms"
                }
            }
            lastFound = reading.found
            inBreak = shown != null
            leftMs = shown?.remainingMs
            if (watch.over) dismissed = false
            delay(reading.waitMs)
        }
    }
}

/** A look for a break: what was [found], whether the picture has [stalled], and how long until the next look. */
private class BreakReading(val found: AdBreakNow?, val stalled: Boolean, val waitMs: Long)

private suspend fun readBreak(player: LivePlayer, sessions: PlaybackSessions): BreakReading {
    if (player.readsAdMarks) return BreakReading(player.adBreak(), stalled = false, waitMs = AD_BREAK_POLL_MS)
    // With nothing loaded past the picture the picture is stalled, and the server can't place it.
    val ahead = player.loadedAheadMs ?: return BreakReading(null, stalled = true, waitMs = AD_BREAK_ASK_MS)
    return runCatchingCancellable {
        BreakReading(sessions.adBreak(ahead), stalled = false, waitMs = AD_BREAK_ASK_MS)
    }.getOrElse { e ->
        // A server from before the break screen, or one between sessions: ask again in a while.
        log.d { "ad break: ask failed: ${e.message}" }
        BreakReading(null, stalled = false, waitMs = AD_BREAK_RETRY_MS)
    }
}

/** The break screen's state for the channel tuned to [tunedId], following its breaks while [covers] is on. */
@Composable
internal fun rememberBreakCover(
    player: LivePlayer,
    sessions: PlaybackSessions,
    covers: Boolean,
    tunedId: String?,
): BreakCover {
    val cover = remember { BreakCover() }
    LaunchedEffect(player, covers, tunedId) {
        cover.reset()
        if (covers) cover.watch(player, sessions)
    }
    return cover
}

/**
 * Whether [key] goes to the break screen while it is up: OK with nothing else on screen, or Back with no panel open.
 * The break screen leaves on the key-up; the info bar and zapping work over it as ever. Over a failure the keys work
 * the failure card instead.
 */
internal fun breakScreenTakes(key: Key, state: PlayerState): Boolean {
    val controlsOpen = state.miniGuideOpen || state.streamCardOpen || state.buttonsFocused
    if (state.failure != null || controlsOpen) return false
    return key == Key.Back || (key.isOk && !state.overlayVisible && !state.numbers.hasDigits)
}
