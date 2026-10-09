package dev.jamesrich.sofaking.ui.player

import androidx.media3.common.C

/** What the caption toggle announces after a press. */
internal enum class CaptionToggleOutcome { ON, OFF, NO_CAPTIONS_ON_CHANNEL }

internal object CaptionPolicy {
    const val PREFERRED_LANGUAGE = "en"

    /** Bottom overscan on full-screen content, per the workspace TV rules. */
    const val OVERSCAN_BOTTOM_DP = 27

    fun hasTextTrack(trackTypes: Iterable<Int>): Boolean = trackTypes.any { it == C.TRACK_TYPE_TEXT }

    /** A channel without text tracks is reported even when captions are switched on; the preference still flips. */
    fun toggleOutcome(enabledAfter: Boolean, channelHasText: Boolean): CaptionToggleOutcome = when {
        !channelHasText -> CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL
        enabledAfter -> CaptionToggleOutcome.ON
        else -> CaptionToggleOutcome.OFF
    }

    /** SubtitleView bottom padding as a fraction of its height, so cues clear the overscan band. */
    fun bottomPaddingFraction(screenHeightDp: Int): Float {
        require(screenHeightDp > 0) { "screen height must be positive: $screenHeightDp" }
        return OVERSCAN_BOTTOM_DP.toFloat() / screenHeightDp
    }
}
