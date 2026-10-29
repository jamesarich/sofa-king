package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.EARLY_END_MS
import dev.jamesrich.sofaking.data.EarlyEnd
import dev.jamesrich.sofaking.data.EarlyEndGuard
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.converterRetryDelayMs
import dev.jamesrich.sofaking.ui.uptimeMs
import kotlinx.coroutines.delay

/**
 * What the library and recordings player's keys do beyond its own state: the stream, captions, focus and the way out.
 */
internal interface VodControls {
    /** Moves to [targetMs] in the item. */
    fun seek(targetMs: Long)

    fun playPause()

    /** A failed item opens or plays again from where it stopped. */
    fun retry()

    /** CC and C: a recording's captions, or a library item's subtitles, on or off. */
    fun toggleCaptions()

    /** The Zoom key: the picture's next [VideoZoom]. */
    fun zoom()

    /** Focus moves into the bar's buttons. */
    fun focusButtons()

    /** Focus moves back to the picture from the bar's buttons or the track card. */
    fun focusPicture()

    /** The Up next card's item plays now. */
    fun playNext()

    /** The Stop key: leaves the player, as Back does. */
    fun exit()

    /** Back to live: the channel a recording still being written comes from. */
    fun backToLive() = Unit
}

/**
 * The library and recordings player's UI state and its key handling, kept out of the composable so they can be
 * tested: where the item is and how long it is, a scrub being built, the failure, the Up next card, and which of the
 * bar, its buttons and the track card has the keys. Keys reach it through [onKeyEvent] and the system Back through
 * [back]; what they do to the stream goes through [VodControls].
 */
@Stable
internal class VodPlayerState(
    private val panels: VodPanelState = VodPanelState(),
    private val skips: VodSkipState = VodSkipState(panels),
    private val scrubbing: VodScrubState = VodScrubState(panels),
) : VodPanels by panels,
    VodSkips by skips,
    VodScrubbing by scrubbing {
    /** The position in the item, which the screen's clock moves on about four times a second. */
    var positionMs by mutableLongStateOf(0L)

    var durationMs by mutableStateOf<Long?>(null)

    /** The failure card's code, while the item has failed. */
    var failure by mutableStateOf<String?>(null)

    /** The server has every converter busy, and [waitForConverter] is asking it again. */
    var waitingForConverter by mutableStateOf(false)
        private set

    /** Playback reached the end of the item. */
    var ended by mutableStateOf(false)
        private set

    /** Bumped when the stream ends well short of the item, so the screen opens it again where it stopped. */
    var reopenRequest by mutableIntStateOf(0)
        private set

    /** What the Up next card counts down to, once the item has ended and there is one. */
    var next by mutableStateOf<VodUpNext?>(null)

    private val earlyEnd = EarlyEndGuard()

    private val keys = VodKeyHandler(this, panels, skips, scrubbing)

    // The countdown changes under its readers, but a delegated val counts as stable, so the compiler would carry a
    // null check over to later reads of it; a getter of its own makes each read a fresh one.
    override val autoSkipSeconds: Int? get() = skips.autoSkipSeconds

    /** A tap on the picture: the bar toggles. */
    fun tap() {
        cancelAutoSkip()
        if (overlayVisible) overlayVisible = false else showInfo()
    }

    /**
     * The stream's state moved on: playing clears a failure, and the end of an opened item ends it. A stream that ends
     * more than [EARLY_END_MS] short of the item's length dropped rather than finished, so [EarlyEndGuard] decides
     * whether it opens again where it stopped or fails.
     */
    fun onStatus(status: PlayerStatus, opened: Boolean) {
        if (status.state == PlaybackState.READY || status.isPlaying) failure = null
        if (status.state != PlaybackState.ENDED || !opened) return
        when (earlyEnd.onEnd(positionMs, durationMs)) {
            EarlyEnd.FINISHED -> ended = true
            EarlyEnd.REOPEN -> reopenRequest++
            EarlyEnd.FAIL -> failure = STREAM_ENDED_EARLY
        }
    }

    /**
     * Runs [open] (the item's open, a restart in a converted file, a track switch), asking again after
     * [converterRetryDelayMs] while the server answers that every converter is busy, and then throws as [open] did.
     * Any other failure throws at once.
     */
    suspend fun <T> waitForConverter(open: suspend () -> T): T {
        var waitedMs = 0L
        try {
            while (true) {
                val retryMs = try {
                    return open()
                } catch (e: SofaKingException) {
                    if (e.code != Wire.ErrorCode.CAPACITY_EXHAUSTED) throw e
                    converterRetryDelayMs(waitedMs) ?: throw e
                }
                waitingForConverter = true
                delay(retryMs)
                waitedMs += retryMs
            }
        } finally {
            waitingForConverter = false
        }
    }

    /**
     * The system Back: a focused button hands focus to the picture, the track card closes, a scrub is
     * dropped; true when the player is left.
     */
    fun back(controls: VodControls): Boolean {
        cancelAutoSkip()
        when {
            // Where Back skips the key path (Android with predictive back), a focused bar button hands focus back here.
            buttonsFocused -> controls.focusPicture()

            tracksOpen -> {
                panels.closeTracks()
                controls.focusPicture()
            }

            scrubTarget != null -> {
                scrubbing.scrub.cancel()
                scrubbing.endScrub()
            }

            else -> return true
        }
        return false
    }

    /**
     * A key event on the player surface, before the focused button or card sees it; true when the player took it.
     * [paused] is whether playback is held, [hasButtons] whether the bar has buttons to move into, and
     * [tracksPickable] whether the track card has anything to pick.
     */
    fun onKeyEvent(
        key: Key,
        type: KeyEventType,
        controls: VodControls,
        paused: Boolean,
        hasButtons: Boolean,
        tracksPickable: Boolean,
        repeatCount: Int? = null,
        nowMs: Long = uptimeMs(),
    ): Boolean = keys.onKeyEvent(key, type, controls, paused, hasButtons, tracksPickable, repeatCount, nowMs)
}

internal const val STREAM_ENDED_EARLY = "STREAM_ENDED_EARLY"

/** What [VodPlayerState.transport] confirms. */
internal enum class TransportHint { PLAY, PAUSE }
