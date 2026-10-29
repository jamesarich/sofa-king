package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/** The library player's keys, on the same state as [state]; [panels]' and [scrubbing]'s own fields move with them. */
internal class VodKeyHandler(
    private val state: VodPlayerState,
    private val panels: VodPanelState,
    skips: VodSkipState,
    private val scrubbing: VodScrubState,
) : VodPanels by panels,
    VodSkips by skips,
    VodScrubbing by scrubbing {
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
        repeatCount: Int?,
        nowMs: Long,
    ): Boolean {
        if (type == KeyEventType.KeyDown) cancelAutoSkip()
        val taken = when {
            // The card's rows take the D-pad and OK; Back closes it through the BackHandler.
            tracksOpen -> {
                tracksUsed()
                false
            }

            state.next != null -> upNextKey(key, type, controls)

            else -> buttonRowKey(key, type, controls)
        }
        val action = key.vodAction()
        if (taken == null && action != null && type == KeyEventType.KeyDown) {
            act(action, key, controls, paused, hasButtons, tracksPickable, repeatCount, nowMs)
        }
        return taken ?: (action != null)
    }

    /** With the Up next card up, OK and play/pause play it now; Back leaves through the BackHandler. */
    private fun upNextKey(key: Key, type: KeyEventType, controls: VodControls): Boolean {
        if (key !in UP_NEXT_PLAY_KEYS) return false
        if (type == KeyEventType.KeyUp) controls.playNext()
        return true
    }

    /** A key while the button row has focus: the row's own keys stay there; null when the key goes on. */
    private fun buttonRowKey(key: Key, type: KeyEventType, controls: VodControls): Boolean? {
        if (!buttonsFocused) return null
        showInfo()
        return when (key.inButtonRow()) {
            ButtonRowKey.ROW -> false

            // Up, Back and Escape hand focus back to the picture on key-up, so the key-up cannot act there too.
            ButtonRowKey.LEAVE -> {
                if (type == KeyEventType.KeyUp) controls.focusPicture()
                true
            }

            ButtonRowKey.PLAYER -> {
                controls.focusPicture()
                null
            }
        }
    }

    /** What a key-down of [action] does with the picture in front. */
    private fun act(
        action: VodAction,
        key: Key,
        controls: VodControls,
        paused: Boolean,
        hasButtons: Boolean,
        tracksPickable: Boolean,
        repeatCount: Int?,
        nowMs: Long,
    ) {
        when (action) {
            VodAction.BACK, VodAction.FORWARD -> if (state.failure == null) scrubStep(action, repeatCount, nowMs)

            VodAction.PLAY_PAUSE -> playPauseKey(key, controls, paused)

            VodAction.PLAY -> if (paused) mediaPlayPause(controls, paused)

            VodAction.PAUSE -> if (!paused) mediaPlayPause(controls, paused)

            VodAction.SHOW_INFO -> if (overlayVisible && !paused && key == Key.Info) {
                overlayVisible = false
            } else {
                showInfo()
            }

            // Down shows the bar, and on the bar moves into its buttons.
            VodAction.BUTTONS -> {
                if (overlayVisible && hasButtons) controls.focusButtons()
                showInfo()
            }

            VodAction.CAPTIONS -> controls.toggleCaptions()

            VodAction.TRACKS -> if (tracksPickable && state.failure == null) openTracks() else showInfo()

            VodAction.ZOOM -> controls.zoom()

            VodAction.STOP -> controls.exit()
        }
    }

    /** Back or Forward moves the scrub a step, further the longer the key is held. */
    private fun scrubStep(action: VodAction, repeatCount: Int?, nowMs: Long) {
        // With the bar down a scrub builds under a badge instead, so the picture stays clear.
        if (overlayVisible) showInfo()
        val direction = if (action == VodAction.BACK) -1 else 1
        scrubbing.scrubTarget =
            scrubbing.scrub.press(direction, state.positionMs, state.durationMs, repeatCount ?: 0, nowMs)
    }

    /** Play/pause retries a failed stream, commits a scrub, or plays or pauses. */
    private fun playPauseKey(key: Key, controls: VodControls, paused: Boolean) {
        when {
            state.failure != null -> controls.retry()

            scrubTarget != null -> {
                scrubbing.scrubTarget = null
                scrubbing.scrub.commit()?.let(controls::seek)
                showInfo()
            }

            key in QUIET_PLAY_KEYS -> mediaPlayPause(controls, paused)

            else -> playPause(controls)
        }
    }

    private fun playPause(controls: VodControls) {
        controls.playPause()
        showInfo()
    }

    /**
     * A media key's play or pause: with the bar up it stays up for another while, and with it down it stays down and
     * the picture's center confirms the press.
     */
    private fun mediaPlayPause(controls: VodControls, paused: Boolean) {
        if (overlayVisible) return playPause(controls)
        controls.playPause()
        panels.transport = if (paused) TransportHint.PLAY else TransportHint.PAUSE
        panels.transportPulse++
    }
}

private val UP_NEXT_PLAY_KEYS =
    setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.Spacebar)

/**
 * The play and pause keys that leave the bar down: the remote's media keys, and Space and K on a keyboard. OK still
 * brings the bar up, as Up and Down do.
 */
private val QUIET_PLAY_KEYS = setOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.Spacebar, Key.K)
