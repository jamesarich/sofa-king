package dev.jamesrich.sofaking.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.Channel

/** Captions on the live player and the timing of each channel's, adjusted from the caption toast. */
internal interface LiveCaptions {
    var captionsOn: Boolean

    var captionToast: CaptionToast?

    /** Each channel's captioner runs a different distance behind; adjustments show before the store round-trips. */
    val advanceOverrides: Map<String, Int>

    /** Captions were switched; the toast offers the timing for a few seconds where the engine applies one. */
    fun captionsToggled(enabled: Boolean, channelHasText: Boolean, leadSupported: Boolean, timingMs: Int)

    /** The caption timing of the channel on screen, as adjusted here or else as [stored] keeps it. */
    fun captionAdvance(stored: (Channel) -> Int): Int

    /**
     * One step earlier (Right) or later (Left) from [advance] for this channel; the toast
     * restarts its window at the new timing.
     */
    fun adjustCaptionTiming(direction: Int, advance: Int, onChanged: (Channel, Int) -> Unit)
}

/** [LiveCaptions], starting [captionsOn], for the channel [currentChannel] gives. */
internal class LiveCaptionState(captionsOn: Boolean, private val currentChannel: () -> Channel?) : LiveCaptions {
    override var captionsOn: Boolean by mutableStateOf(captionsOn)

    override var captionToast: CaptionToast? by mutableStateOf<CaptionToast?>(null)

    override var advanceOverrides: Map<String, Int> by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    override fun captionsToggled(enabled: Boolean, channelHasText: Boolean, leadSupported: Boolean, timingMs: Int) {
        captionsOn = enabled
        captionToast =
            CaptionToast(
                CaptionPolicy.toggleOutcome(enabled, channelHasText),
                timingMs.takeIf { enabled && leadSupported },
            )
    }

    override fun captionAdvance(stored: (Channel) -> Int): Int =
        currentChannel()?.let { advanceOverrides[it.guideId] ?: stored(it) } ?: 0

    override fun adjustCaptionTiming(direction: Int, advance: Int, onChanged: (Channel, Int) -> Unit) {
        val channel = currentChannel() ?: return
        val toast = captionToast ?: return
        val next = CaptionPolicy.stepAdvance(advance, direction)
        advanceOverrides = advanceOverrides + (channel.guideId to next)
        onChanged(channel, next)
        captionToast = CaptionToast(toast.outcome, next)
    }
}
