package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.AppSettings
import kotlin.math.abs

/** Where a caption timing puts the text against the stream's own timing. */
internal enum class CaptionTiming { EARLIER, LATER, AS_BROADCAST }

/** What the caption toggle announces after a press. */
internal enum class CaptionToggleOutcome { ON, OFF, NO_CAPTIONS_ON_CHANNEL }

/** A text track as [CaptionPolicy] sees it: its position in the player's tracks and what it carries. */
internal data class TextTrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val mimeType: String?,
    val accessibilityChannel: Int,
)

internal object CaptionPolicy {
    const val PREFERRED_LANGUAGE = "en"

    /** Media3's sample MIME types for the two caption standards the relays carry. */
    const val MIME_CEA608 = "application/cea-608"
    const val MIME_CEA708 = "application/cea-708"

    /** Bottom overscan on full-screen content, the same vertical margin every screen keeps. */
    const val OVERSCAN_BOTTOM_DP = 27

    private const val SERVICE_ONE = 1

    /** A channel without text tracks is reported even when captions are switched on; the preference still flips. */
    fun toggleOutcome(enabledAfter: Boolean, channelHasText: Boolean): CaptionToggleOutcome = when {
        !channelHasText -> CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL
        enabledAfter -> CaptionToggleOutcome.ON
        else -> CaptionToggleOutcome.OFF
    }

    /**
     * Streams often carry both CEA-708 and CEA-608. CEA-708 service 1 wins, then any 708 service, then
     * CEA-608 CC1, then any 608 track, then whatever text track there is. Null when there are none.
     */
    fun preferredTextTrack(tracks: List<TextTrackInfo>): TextTrackInfo? =
        tracks.firstOrNull { it.isCea708() && it.accessibilityChannel == SERVICE_ONE }
            ?: tracks.firstOrNull { it.isCea708() }
            ?: tracks.firstOrNull { it.isCea608() && it.accessibilityChannel == SERVICE_ONE }
            ?: tracks.firstOrNull { it.isCea608() }
            ?: tracks.firstOrNull()

    private fun TextTrackInfo.isCea708() = mimeType == MIME_CEA708

    private fun TextTrackInfo.isCea608() = mimeType == MIME_CEA608

    /** Bottom inset for captions as a fraction of the height, so cues clear the overscan band. */
    fun bottomPaddingFraction(screenHeightDp: Int): Float {
        require(screenHeightDp > 0) { "screen height must be positive: $screenHeightDp" }
        return OVERSCAN_BOTTOM_DP.toFloat() / screenHeightDp
    }

    /** Caption timing in seconds with one decimal place and no sign, as shown in the overlay and the settings row. */
    fun advanceLabel(ms: Int): String {
        val magnitude = abs(ms)
        return "${magnitude / 1_000}.${(magnitude % 1_000) / 100}"
    }

    /** Which way a timing moves captions: earlier than the stream puts them, later, or not at all. */
    fun timingDirection(ms: Int): CaptionTiming = when {
        ms > 0 -> CaptionTiming.EARLIER
        ms < 0 -> CaptionTiming.LATER
        else -> CaptionTiming.AS_BROADCAST
    }

    /** One step earlier (`direction` > 0) or later (< 0), clamped to [range]. */
    fun stepAdvance(
        currentMs: Int,
        direction: Int,
        range: IntRange = AppSettings.CAPTION_ADVANCE_RANGE_MS,
        stepMs: Int = AppSettings.CAPTION_ADVANCE_STEP_MS,
    ): Int {
        val delta = if (direction > 0) stepMs else -stepMs
        return (currentMs + delta).coerceIn(range)
    }
}
