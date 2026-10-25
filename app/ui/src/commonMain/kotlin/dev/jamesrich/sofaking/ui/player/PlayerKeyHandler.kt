package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.jamesrich.sofaking.ui.isOk

/** The live player's keys, on the same state as [PlayerState]; [panels]' counters move with each key. */
internal class PlayerKeyHandler(
    ring: ChannelRing,
    private val panels: PanelState,
    captions: LiveCaptionState,
    private val numbers: ChannelNumberInput,
) : ChannelZapping by ring,
    PlayerPanels by panels,
    LiveCaptions by captions {
    private val centerGesture = CenterKeyGesture()

    private val infoGesture = CenterKeyGesture()

    /**
     * A key event on the player surface, before the focused panel sees it; true when the player took it.
     * [hasAudioTracks] says whether the stream card lists tracks to pick.
     */
    fun onKeyEvent(
        key: Key,
        type: KeyEventType,
        controls: PlayerControls,
        isLongPress: Boolean,
        repeatCount: Int?,
        hasAudioTracks: Boolean,
    ): Boolean = buttonRowKey(key, type, controls)
        ?: backKey(key, type)
        ?: when {
            key == Key.Info -> onInfo(type, isLongPress, repeatCount)
            key.isOk -> okKey(type, isLongPress, repeatCount, controls, hasAudioTracks)
            type != KeyEventType.KeyDown || channels.isEmpty() -> false
            streamCardOpen -> streamCardKey(key, controls)
            miniGuideOpen -> miniGuideKey(key, controls)
            else -> onKey(key, controls)
        }

    /** A key while the button row has focus: the row's own keys stay there; null when the key goes on. */
    private fun buttonRowKey(key: Key, type: KeyEventType, controls: PlayerControls): Boolean? {
        if (!buttonsFocused) return null
        // Each key keeps the bar up for another while.
        panels.overlayRequest++
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

    /**
     * The mini-guide's focused list swallows Back before BackHandler sees it, so Back closes it here and eats the
     * matching key-up, so the same press can't also leave the player. Null when the key goes on.
     */
    private fun backKey(key: Key, type: KeyEventType): Boolean? = when {
        key != Key.Back -> null

        type == KeyEventType.KeyDown && (miniGuideOpen || streamCardOpen) -> {
            miniGuideOpen = false
            streamCardOpen = false
            panels.swallowBackUp = true
            true
        }

        type == KeyEventType.KeyUp && swallowBackUp -> {
            panels.swallowBackUp = false
            true
        }

        else -> null
    }

    private fun okKey(
        type: KeyEventType,
        isLongPress: Boolean,
        repeatCount: Int?,
        controls: PlayerControls,
        hasAudioTracks: Boolean,
    ): Boolean = when {
        !streamCardOpen -> onCenter(type, isLongPress, repeatCount, controls)

        // The focused track row takes OK; with nothing to pick, OK closes the card on its key-up.
        streamCardOk(hasAudioTracks) == StreamCardKey.CARD -> false

        else -> {
            if (type == KeyEventType.KeyUp) streamCardOpen = false
            true
        }
    }

    private fun streamCardKey(key: Key, controls: PlayerControls): Boolean {
        panels.streamCardActivity++
        return when (key.inStreamCard()) {
            // The focused track row handles OK.
            StreamCardKey.CARD -> false

            StreamCardKey.CLOSE -> {
                streamCardOpen = false
                true
            }

            StreamCardKey.PLAYER -> {
                streamCardOpen = false
                onKey(key, controls)
            }
        }
    }

    private fun miniGuideKey(key: Key, controls: PlayerControls): Boolean {
        panels.miniGuideActivity++
        return when (key.inMiniGuide(miniGuideChipsFocused)) {
            // The focused row or chip handles OK.
            MiniGuideKey.LIST -> false

            MiniGuideKey.SWALLOWED -> true

            MiniGuideKey.PLAYER -> onKey(key, controls)
        }
    }

    /** OK is judged on key-up: a short press acts as [playerOk] decides, a long press recalls the last channel. */
    private fun onCenter(
        type: KeyEventType,
        isLongPress: Boolean,
        repeatCount: Int?,
        controls: PlayerControls,
    ): Boolean {
        // With the mini-guide open, its focused row, chip or button takes OK; the press still
        // counts against its idle timer.
        if (miniGuideOpen) {
            panels.miniGuideActivity++
            return false
        }
        if (channels.isEmpty()) return false
        return when (type) {
            KeyEventType.KeyDown -> {
                if (centerGesture.onKeyDown(isLongPress, repeatCount) == CenterPress.LONG) recallLastChannel()
                true
            }

            KeyEventType.KeyUp -> {
                if (centerGesture.onKeyUp() == CenterPress.SHORT) {
                    when (playerOk(numbers.hasDigits, failure != null && currentChannel != null, overlayVisible)) {
                        PlayerOk.COMMIT_NUMBER -> numbers.commit()
                        PlayerOk.RETRY -> controls.retry()
                        PlayerOk.OPEN_CHANNEL_LIST -> openMiniGuide()
                        PlayerOk.SHOW_INFO -> showInfo()
                    }
                }
                true
            }

            else -> false
        }
    }

    private fun onKey(key: Key, controls: PlayerControls): Boolean {
        if (channels.isEmpty()) return false
        val exit = key.playerExit()
        val digit = key.digitValue()
        val action = key.playerAction()
        when {
            exit == PlayerExit.GUIDE_AT_NOW -> controls.guide()
            exit == PlayerExit.SEARCH -> controls.search()
            digit != null -> numbers.append(digit)
            action != null -> act(action, controls)
            else -> return false
        }
        return true
    }

    private fun act(action: PlayerAction, controls: PlayerControls) {
        when (action) {
            PlayerAction.TOGGLE_CAPTIONS -> controls.toggleCaptions()

            PlayerAction.ZAP_UP, PlayerAction.ZAP_DOWN, PlayerAction.CHANNEL_UP, PlayerAction.CHANNEL_DOWN ->
                action.zapStep()?.let(::zapStep)

            PlayerAction.LAST_CHANNEL -> recallLastChannel()

            // The bar comes up with the change and, paused, stays up, as the library player's does.
            PlayerAction.PLAY_PAUSE -> {
                controls.playPause()
                showInfo()
            }

            PlayerAction.PLAY -> {
                controls.play()
                showInfo()
            }

            PlayerAction.PAUSE -> {
                controls.pause()
                showInfo()
            }

            PlayerAction.TOGGLE_INFO -> if (!numbers.hasDigits) overlayVisible = !overlayVisible

            PlayerAction.LEFT, PlayerAction.RIGHT -> {
                val adjustable = captionToast?.timingMs != null && captionsOn
                when (playerSideKey(adjustable, overlayVisible)) {
                    // Right shows captions earlier, Left later.
                    PlayerSideKey.ADJUST_CAPTION_TIMING -> controls.adjustCaptionTiming(
                        if (action == PlayerAction.RIGHT) {
                            1
                        } else {
                            -1
                        },
                    )

                    PlayerSideKey.FOCUS_BUTTONS -> {
                        controls.focusButtons()
                        keepInfo()
                    }

                    PlayerSideKey.SHOW_INFO -> showInfo()
                }
            }

            PlayerAction.STREAM_INFO -> openStreamCard()

            PlayerAction.RECORD -> controls.record()

            PlayerAction.ZOOM -> controls.zoom()

            PlayerAction.STOP -> controls.stop()
        }
    }

    /** Info is judged on key-up: a short press toggles the info bar, a long press opens the stream card. */
    private fun onInfo(type: KeyEventType, isLongPress: Boolean, repeatCount: Int?): Boolean {
        when (type) {
            KeyEventType.KeyDown -> if (infoGesture.onKeyDown(isLongPress, repeatCount) == CenterPress.LONG) {
                openStreamCard()
            }

            KeyEventType.KeyUp -> if (infoGesture.onKeyUp() == CenterPress.SHORT) {
                when {
                    streamCardOpen -> streamCardOpen = false

                    miniGuideOpen -> {
                        miniGuideOpen = false
                        showInfo()
                    }

                    !numbers.hasDigits -> overlayVisible = !overlayVisible
                }
            }

            else -> return false
        }
        return true
    }
}
