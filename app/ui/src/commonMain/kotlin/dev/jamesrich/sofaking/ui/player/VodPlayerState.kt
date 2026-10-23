package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.jamesrich.sofaking.ui.uptimeMs

/** What the library and recordings player's keys do beyond its own state: the stream, captions, focus and the way out. */
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
internal class VodPlayerState {
    /** The position in the item, which the screen's clock moves on about four times a second. */
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableStateOf<Long?>(null)

    /** The failure card's code, while the item has failed. */
    var failure by mutableStateOf<String?>(null)

    /** Playback reached the end of the item. */
    var ended by mutableStateOf(false)
        private set

    /** Bumped when the stream ends well short of the item, so the screen opens it again where it stopped. */
    var reopenRequest by mutableIntStateOf(0)
        private set

    /** What the Up next card counts down to, once the item has ended and there is one. */
    var next by mutableStateOf<VodUpNext?>(null)

    var overlayVisible by mutableStateOf(true)

    /** Bumped to keep the bar up for another while. */
    var overlayRequest by mutableIntStateOf(0)
        private set

    var tracksOpen by mutableStateOf(false)
        private set

    // True while a button on the bar has focus, so the D-pad moves along the row instead of seeking.
    var buttonsFocused by mutableStateOf(false)

    /** Where a scrub (Left and Right, or a drag on the slider) would land; null while none is being built. */
    var scrubTarget by mutableStateOf<Long?>(null)
        private set

    // Set when playback runs into a break it does not skip, or is in an intro, so Skip break or Skip intro takes
    // focus and OK skips, as other players' skip pill does.
    var focusSkip by mutableStateOf(false)

    /** The starts of the intros the bar has offered to skip, each once, so a viewer who goes back into one stays there. */
    private val offeredIntros = mutableSetOf<Long>()

    private val scrub = VodScrub()

    // Ends short of the item's end since playback last moved on, and where the last one was.
    private var earlyEnds = 0
    private var earlyEndAtMs = 0L

    fun showInfo() {
        overlayVisible = true
        overlayRequest++
    }

    /** The track card stands in for the bar, which would otherwise run under it; the next key brings the bar back. */
    fun openTracks() {
        tracksOpen = true
        overlayVisible = false
    }

    fun closeTracks() {
        tracksOpen = false
    }

    /** A tap on the picture: the bar toggles. */
    fun tap() {
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

    /** Playback ran into a break it does not skip: the bar comes up with Skip break on it, focused for the remote. */
    fun ranIntoBreak() {
        showInfo()
        focusSkip = true
    }

    /** Playback is in [intro]: the first time, the bar comes up with Skip intro on it, focused for the remote. */
    fun inIntro(intro: VodMarker) {
        if (offeredIntros.add(intro.startMs)) ranIntoBreak()
    }

    /** A finger or a mouse dragging the slider: the times follow [targetMs] until [endScrub]. */
    fun dragTo(targetMs: Long) {
        scrubTarget = targetMs
        showInfo()
    }

    fun endScrub() {
        scrubTarget = null
    }

    /** The scrub to apply once the presses have rested at [nowMs], clearing it; null while they have not. */
    fun settledScrub(nowMs: Long): Long? = scrub.settled(nowMs)?.also { scrubTarget = null }

    /** The system Back: a focused button hands focus to the picture, the track card closes, a scrub is dropped; true when the player is left. */
    fun back(controls: VodControls): Boolean {
        when {
            // Where Back skips the key path (Android with predictive back), a focused bar button hands focus back here.
            buttonsFocused -> controls.focusPicture()
            tracksOpen -> {
                tracksOpen = false
                controls.focusPicture()
            }
            scrubTarget != null -> {
                scrub.cancel()
                scrubTarget = null
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
    ): Boolean {
        // The card's rows take the D-pad and OK; Back closes it through the BackHandler.
        if (tracksOpen) return false
        if (next != null) {
            // With the Up next card up, OK and play/pause play it now; Back leaves through the BackHandler.
            if (key !in UP_NEXT_PLAY_KEYS) return false
            if (type == KeyEventType.KeyUp) controls.playNext()
            return true
        }
        if (buttonsFocused) {
            showInfo()
            when (key.inButtonRow()) {
                ButtonRowKey.ROW -> return false
                // Up, Back and Escape hand focus back to the picture on key-up, so the key-up cannot act there too.
                ButtonRowKey.LEAVE -> {
                    if (type == KeyEventType.KeyUp) controls.focusPicture()
                    return true
                }
                ButtonRowKey.PLAYER -> controls.focusPicture()
            }
        }
        val action = key.vodAction() ?: return false
        if (type != KeyEventType.KeyDown) return true
        when (action) {
            VodAction.BACK, VodAction.FORWARD -> {
                if (failure != null) return true
                showInfo()
                val direction = if (action == VodAction.BACK) -1 else 1
                scrubTarget = scrub.press(direction, positionMs, durationMs, repeatCount ?: 0, nowMs)
            }
            VodAction.PLAY_PAUSE -> when {
                failure != null -> controls.retry()
                scrubTarget != null -> {
                    scrubTarget = null
                    scrub.commit()?.let(controls::seek)
                    showInfo()
                }
                else -> playPause(controls)
            }
            VodAction.PLAY -> if (paused) playPause(controls)
            VodAction.PAUSE -> if (!paused) playPause(controls)
            VodAction.SHOW_INFO -> if (overlayVisible && !paused && key == Key.Info) overlayVisible = false else showInfo()
            // Down shows the bar, and on the bar moves into its buttons.
            VodAction.BUTTONS -> {
                if (overlayVisible && hasButtons) controls.focusButtons()
                showInfo()
            }
            VodAction.CAPTIONS -> controls.toggleCaptions()
            VodAction.TRACKS -> if (tracksPickable && failure == null) openTracks() else showInfo()
            VodAction.ZOOM -> controls.zoom()
            VodAction.STOP -> controls.exit()
        }
        return true
    }

    private fun playPause(controls: VodControls) {
        controls.playPause()
        showInfo()
    }
}

/** How far short of the item's length an end may come and still be the end: a conversion can stop a little short. */
internal const val EARLY_END_MS = 10_000L
private const val EARLY_END_REOPENS = 2
private const val EARLY_END_PROGRESS_MS = 30_000L
internal const val STREAM_ENDED_EARLY = "STREAM_ENDED_EARLY"

private val UP_NEXT_PLAY_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.Spacebar)
