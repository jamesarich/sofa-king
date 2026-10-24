package dev.jamesrich.sofaking.ui.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput

/**
 * Holds caption updates that [CaptionSmoother] says are mid-line, then passes them on whole. Calls and
 * the held flush both run on [looper], the player's output thread, so no locking is needed.
 */
@UnstableApi
class SmoothingTextOutput(private val delegate: TextOutput, looper: Looper, private val enabled: () -> Boolean) :
    TextOutput {
    private val handler = Handler(looper)
    private val flush = Runnable {
        flushScheduled = false
        pending?.let { held ->
            pending = null
            emit(held, textOf(held), SystemClock.uptimeMillis())
        }
    }
    private var shownText = ""
    private var lastEmitMs = 0L
    private var pending: CueGroup? = null
    private var flushScheduled = false

    override fun onCues(cueGroup: CueGroup) {
        val now = SystemClock.uptimeMillis()
        if (!enabled()) {
            cancelHeld()
            emit(cueGroup, textOf(cueGroup), now)
            return
        }
        val text = textOf(cueGroup)
        when (CaptionSmoother.decide(shownText, text, now, lastEmitMs)) {
            CaptionSmoother.Decision.EMIT -> {
                cancelHeld()
                emit(cueGroup, text, now)
            }

            CaptionSmoother.Decision.HOLD -> {
                // Latest wins: the flush reads whatever is pending when it fires.
                pending = cueGroup
                if (!flushScheduled) {
                    flushScheduled = true
                    handler.postDelayed(flush, CaptionSmoother.HOLD_MS - (now - lastEmitMs))
                }
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCues(cues: List<Cue>) = onCues(CueGroup(cues, C.TIME_UNSET))

    private fun cancelHeld() {
        pending = null
        if (flushScheduled) {
            handler.removeCallbacks(flush)
            flushScheduled = false
        }
    }

    private fun emit(group: CueGroup, text: String, nowMs: Long) {
        shownText = text
        lastEmitMs = nowMs
        delegate.onCues(group)
    }

    private fun textOf(group: CueGroup): String = group.cues.joinToString("\n") { it.text?.toString().orEmpty() }
}
