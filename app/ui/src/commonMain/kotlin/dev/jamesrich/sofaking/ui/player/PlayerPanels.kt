package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.AppSettings

/** Which of the info bar, the channel list and the stream card shows, and how Back and taps close them. */
internal interface PlayerPanels {
    var overlayVisible: Boolean

    /** Bumped to keep the info bar up for another while. */
    val overlayRequest: Int

    var miniGuideOpen: Boolean

    /** Bumped on every key while the mini-guide is open, so its idle timer restarts. */
    val miniGuideActivity: Int

    /** True while a chip of the channel list has focus, so Left and Right move along the chips. */
    var miniGuideChipsFocused: Boolean

    /** True while a button of the info bar has focus, so the D-pad moves along them instead of zapping. */
    var buttonsFocused: Boolean

    var streamCardOpen: Boolean

    val streamCardActivity: Int

    var streamInfo: StreamInfo

    var streamBufferedMs: Long

    /** The key legend rides the first few info bars after install, counted once per bar. */
    val legendCount: Int

    val legendOnThisBar: Boolean

    /**
     * The press that closed a panel ends here: with the panel's focused row gone, its key-up can miss the
     * surface's key handler and arrive as a Back of its own.
     */
    val swallowBackUp: Boolean

    fun showInfo()

    /** A touch control acted: the info bar stays up for another while. */
    fun keepInfo()

    fun openMiniGuide()

    fun openStreamCard()

    /** A track was picked on the stream card, which stays open for another while. */
    fun streamCardUsed()

    /**
     * A new info bar shows: it carries the key legend for the first few bars; returns the count to save when it does.
     */
    fun legendForNewBar(): Int?

    /**
     * The system Back: an open panel closes, or a focused bar button hands focus back to the picture with the bar still
     * up, as Up does; true when nothing was open and the player is left.
     */
    fun back(controls: PlayerControls): Boolean

    /**
     * A tap or click on the video: the stream card or the channel list closes, or the info bar
     * and its touch controls toggle.
     */
    fun tap()
}

/** [PlayerPanels], the key legend counted from [keyLegendShown] bars already shown. */
internal class PanelState(keyLegendShown: Int) : PlayerPanels {
    override var overlayVisible: Boolean by mutableStateOf(true)

    override var overlayRequest: Int by mutableIntStateOf(0)

    override var miniGuideOpen: Boolean by mutableStateOf(false)

    override var miniGuideActivity: Int by mutableIntStateOf(0)

    override var miniGuideChipsFocused: Boolean by mutableStateOf(false)

    override var buttonsFocused: Boolean by mutableStateOf(false)

    override var streamCardOpen: Boolean by mutableStateOf(false)

    override var streamCardActivity: Int by mutableIntStateOf(0)

    override var streamInfo: StreamInfo by mutableStateOf(StreamInfo())

    override var streamBufferedMs: Long by mutableLongStateOf(0L)

    override var legendCount: Int by mutableIntStateOf(keyLegendShown)
        private set

    override var legendOnThisBar: Boolean by mutableStateOf(false)
        private set

    override var swallowBackUp: Boolean by mutableStateOf(false)

    override fun showInfo() {
        overlayVisible = true
        overlayRequest++
    }

    override fun keepInfo() {
        overlayRequest++
    }

    override fun openMiniGuide() {
        overlayVisible = false
        streamCardOpen = false
        miniGuideOpen = true
        miniGuideChipsFocused = false
        miniGuideActivity++
    }

    override fun openStreamCard() {
        overlayVisible = false
        miniGuideOpen = false
        streamCardOpen = true
        streamCardActivity++
    }

    override fun streamCardUsed() {
        streamCardActivity++
    }

    override fun legendForNewBar(): Int? {
        legendOnThisBar = legendCount < AppSettings.KEY_LEGEND_BARS
        return if (legendOnThisBar) ++legendCount else null
    }

    override fun back(controls: PlayerControls): Boolean {
        when {
            // The press that closed a panel ends here: with the panel's focused row gone, its key-up can miss the
            // surface's key handler and arrive as a Back of its own.
            swallowBackUp -> swallowBackUp = false

            streamCardOpen -> streamCardOpen = false

            miniGuideOpen -> miniGuideOpen = false

            // Where Back skips the key path (Android with predictive back), it reaches the row only here.
            buttonsFocused -> {
                controls.focusPicture()
                keepInfo()
            }

            else -> return true
        }
        return false
    }

    override fun tap() {
        if (streamCardOpen) {
            streamCardOpen = false
            return
        }
        when (playerTap(miniGuideOpen, overlayVisible)) {
            PlayerTap.CLOSE_CHANNEL_LIST -> miniGuideOpen = false
            PlayerTap.HIDE_INFO -> overlayVisible = false
            PlayerTap.SHOW_INFO -> showInfo()
        }
    }
}
