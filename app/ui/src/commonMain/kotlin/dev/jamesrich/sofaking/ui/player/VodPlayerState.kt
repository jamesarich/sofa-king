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
import dev.jamesrich.sofaking.data.SofaKingException
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

    // Ends short of the item's end since playback last moved on, and where the last one was.
    private var earlyEnds = 0
    private var earlyEndAtMs = 0L

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
     * more than [EARLY_END_MS] short of the item's length dropped rather than finished (a connection or a conversion
     * that died), so it opens again where it stopped, up to [EARLY_END_REOPENS] times until playback moves on by
     * [EARLY_END_PROGRESS_MS], and then fails, so Up next never plays over an episode cut short.
     */
    fun onStatus(status: PlayerStatus, opened: Boolean) {
        if (status.state == PlaybackState.READY || status.isPlaying) failure = null
        if (status.state != PlaybackState.ENDED || !opened) return
        val duration = durationMs
        if (duration == null || positionMs + EARLY_END_MS >= duration) {
            ended = true
            return
        }
        if (positionMs >= earlyEndAtMs + EARLY_END_PROGRESS_MS) earlyEnds = 0
        earlyEndAtMs = positionMs
        if (earlyEnds++ < EARLY_END_REOPENS) reopenRequest++ else failure = STREAM_ENDED_EARLY
    }

    /**
     * Runs [open] (the item's open, a restart in a converted file, a track switch), asking again every
     * [CONVERTER_RETRY_MS] for up to [CONVERTER_WAIT_MS] while the server answers that every converter is busy, and
     * then throws as [open] did. Any other failure throws at once.
     */
    suspend fun <T> waitForConverter(open: suspend () -> T): T {
        var waitedMs = 0L
        try {
            while (true) {
                try {
                    return open()
                } catch (e: SofaKingException) {
                    if (e.code != Wire.ErrorCode.CAPACITY_EXHAUSTED || waitedMs >= CONVERTER_WAIT_MS) throw e
                }
                waitingForConverter = true
                delay(CONVERTER_RETRY_MS)
                waitedMs += CONVERTER_RETRY_MS
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

/** How far short of the item's length an end may come and still be the end: a conversion can stop a little short. */
internal const val EARLY_END_MS = 10_000L
private const val EARLY_END_REOPENS = 2
private const val EARLY_END_PROGRESS_MS = 30_000L
internal const val STREAM_ENDED_EARLY = "STREAM_ENDED_EARLY"

/** How long Skip intro counts down before the intro skips itself, with auto skip on. */
internal const val AUTO_SKIP_MS = 5_000L

/** How long the player waits for a free converter before it shows the failure, and how often it asks. */
internal const val CONVERTER_WAIT_MS = 20_000L
internal const val CONVERTER_RETRY_MS = 5_000L

/** What [VodPlayerState.transport] confirms. */
internal enum class TransportHint { PLAY, PAUSE }
