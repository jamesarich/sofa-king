package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.AUTO_SKIP_MS
import dev.jamesrich.sofaking.data.VodMarker
import dev.jamesrich.sofaking.ui.secondsUp
import dev.jamesrich.sofaking.ui.uptimeMs

/** Skip break and Skip intro: when they take focus, and the countdown that skips an intro by itself. */
internal interface VodSkips {
    /**
     * Set when playback runs into a break it does not skip, or is in an intro, so Skip break or Skip intro takes
     * focus and OK skips, as other players' skip pill does.
     */
    var focusSkip: Boolean

    /** Whole seconds until the intro skips itself, counting down on Skip intro; null while it waits for a press. */
    val autoSkipSeconds: Int?

    /**
     * Playback ran into a break it does not skip: the bar comes up with Skip break on it, focused for the remote. Not
     * while the track card is open, which keeps the remote.
     */
    fun ranIntoBreak()

    /**
     * Playback is in [intro], or the credits: the first time, the bar comes up with Skip intro (or Skip credits) on it,
     * focused for the remote, and with [autoSkip] a countdown of [AUTO_SKIP_MS] starts on it. While the track card is
     * open the offer waits for it to close.
     */
    fun inIntro(intro: VodMarker, autoSkip: Boolean = false, nowMs: Long = uptimeMs())

    /** Moves the countdown on to [nowMs]; true when it ran out, so the intro is skipped now. */
    fun autoSkipDue(nowMs: Long): Boolean

    /** The countdown stops and Skip intro waits for a press: any key or tap, or playback leaving the intro. */
    fun cancelAutoSkip()
}

/** [VodSkips], offered on the bar in [panels]. */
internal class VodSkipState(private val panels: VodPanelState) : VodSkips {
    override var focusSkip: Boolean by mutableStateOf(false)

    override var autoSkipSeconds: Int? by mutableStateOf<Int?>(null)
        private set

    /**
     * The starts of the intros the bar has offered to skip, each once, so a viewer who goes back into one stays there.
     */
    private val offeredIntros = mutableSetOf<Long>()

    private var autoSkipAtMs = 0L

    override fun ranIntoBreak() {
        if (panels.tracksOpen) return
        panels.showInfo()
        focusSkip = true
    }

    override fun inIntro(intro: VodMarker, autoSkip: Boolean, nowMs: Long) {
        if (panels.tracksOpen || !offeredIntros.add(intro.startMs)) return
        ranIntoBreak()
        if (autoSkip) {
            autoSkipAtMs = nowMs + AUTO_SKIP_MS
            autoSkipSeconds = secondsUntil(autoSkipAtMs, nowMs)
        }
    }

    override fun autoSkipDue(nowMs: Long): Boolean {
        if (autoSkipSeconds == null) return false
        if (nowMs >= autoSkipAtMs) {
            autoSkipSeconds = null
            return true
        }
        autoSkipSeconds = secondsUntil(autoSkipAtMs, nowMs)
        return false
    }

    override fun cancelAutoSkip() {
        autoSkipSeconds = null
    }

    private fun secondsUntil(atMs: Long, nowMs: Long): Int = secondsUp(atMs - nowMs)
}
