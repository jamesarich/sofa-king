package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which of the library player's bar, its buttons and the track card shows, and the media keys' hint. */
internal interface VodPanels {
    var overlayVisible: Boolean

    /** Bumped to keep the bar up for another while. */
    val overlayRequest: Int

    val tracksOpen: Boolean

    /**
     * What a play or pause from the remote's media keys just did while the bar was down, which the center of the
     * picture confirms for a moment instead of bringing the bar up; [transportPulse] counts each one, so a repeat of
     * the same one shows again.
     */
    val transport: TransportHint?

    val transportPulse: Int

    /** True while a button on the bar has focus, so the D-pad moves along the row instead of seeking. */
    var buttonsFocused: Boolean

    fun showInfo()

    /** The track card stands in for the bar, which would otherwise run under it; the next key brings the bar back. */
    fun openTracks()

    fun closeTracks()
}

/** [VodPanels] for the library player. */
internal class VodPanelState : VodPanels {
    override var overlayVisible: Boolean by mutableStateOf(true)

    override var overlayRequest: Int by mutableIntStateOf(0)

    override var tracksOpen: Boolean by mutableStateOf(false)

    override var transport: TransportHint? by mutableStateOf<TransportHint?>(null)

    override var transportPulse: Int by mutableIntStateOf(0)

    override var buttonsFocused: Boolean by mutableStateOf(false)

    override fun showInfo() {
        overlayVisible = true
        overlayRequest++
    }

    override fun openTracks() {
        tracksOpen = true
        overlayVisible = false
    }

    override fun closeTracks() {
        tracksOpen = false
    }
}
