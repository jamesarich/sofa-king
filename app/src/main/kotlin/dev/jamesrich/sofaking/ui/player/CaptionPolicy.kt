package dev.jamesrich.sofaking.ui.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import dev.jamesrich.sofaking.data.AppSettings

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

    /** Bottom overscan on full-screen content, per the workspace TV rules. */
    const val OVERSCAN_BOTTOM_DP = 27

    private const val SERVICE_ONE = 1

    fun hasTextTrack(trackTypes: Iterable<Int>): Boolean = trackTypes.any { it == C.TRACK_TYPE_TEXT }

    /** A channel without text tracks is reported even when captions are switched on; the preference still flips. */
    fun toggleOutcome(enabledAfter: Boolean, channelHasText: Boolean): CaptionToggleOutcome = when {
        !channelHasText -> CaptionToggleOutcome.NO_CAPTIONS_ON_CHANNEL
        enabledAfter -> CaptionToggleOutcome.ON
        else -> CaptionToggleOutcome.OFF
    }

    /**
     * Streams often carry both CEA-608 and CEA-708. CEA-608 CC1 wins, then any 608 track, then CEA-708
     * service 1, then any 708 service, then whatever text track there is: Media3's 708 decoder shows each
     * caption for about 100 ms and then clears it. Null when there are none.
     */
    fun preferredTextTrack(tracks: List<TextTrackInfo>): TextTrackInfo? =
        tracks.firstOrNull { it.isCea608() && it.accessibilityChannel == SERVICE_ONE }
            ?: tracks.firstOrNull { it.isCea608() }
            ?: tracks.firstOrNull { it.isCea708() && it.accessibilityChannel == SERVICE_ONE }
            ?: tracks.firstOrNull { it.isCea708() }
            ?: tracks.firstOrNull()

    private fun TextTrackInfo.isCea708() = mimeType == MimeTypes.APPLICATION_CEA708

    private fun TextTrackInfo.isCea608() = mimeType == MimeTypes.APPLICATION_CEA608

    /** SubtitleView bottom padding as a fraction of its height, so cues clear the overscan band. */
    fun bottomPaddingFraction(screenHeightDp: Int): Float {
        require(screenHeightDp > 0) { "screen height must be positive: $screenHeightDp" }
        return OVERSCAN_BOTTOM_DP.toFloat() / screenHeightDp
    }

    /** Caption timing in seconds with one decimal place, as shown in the overlay and the settings row. */
    fun advanceLabel(ms: Int): String {
        require(ms >= 0) { "caption advance must not be negative: $ms" }
        return "${ms / 1_000}.${(ms % 1_000) / 100}"
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
