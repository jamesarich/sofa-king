package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A scrub being built, by the remote's presses or a drag on the slider. */
internal interface VodScrubbing {
    /** Where a scrub (Left and Right, or a drag on the slider) would land; null while none is being built. */
    val scrubTarget: Long?

    /** A finger or a mouse dragging the slider: the times follow [targetMs] until [endScrub]. */
    fun dragTo(targetMs: Long)

    fun endScrub()

    /** The scrub to apply once the presses have rested at [nowMs], clearing it; null while they have not. */
    fun settledScrub(nowMs: Long): Long?
}

/** [VodScrubbing], which brings up the bar in [panels] as a drag moves. */
internal class VodScrubState(private val panels: VodPanelState) : VodScrubbing {
    override var scrubTarget: Long? by mutableStateOf<Long?>(null)

    val scrub = VodScrub()

    override fun dragTo(targetMs: Long) {
        scrubTarget = targetMs
        panels.showInfo()
    }

    override fun endScrub() {
        scrubTarget = null
    }

    override fun settledScrub(nowMs: Long): Long? = scrub.settled(nowMs)?.also { scrubTarget = null }
}
