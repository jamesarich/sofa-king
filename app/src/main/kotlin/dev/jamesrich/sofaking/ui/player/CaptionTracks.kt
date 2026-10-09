package dev.jamesrich.sofaking.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.jamesrich.sofaking.data.CaptionStyle

/** CEA-608 CC1 and CEA-708 service 1, declared together so the SEI reader emits both. */
@OptIn(UnstableApi::class)
internal fun captionFormats(): List<Format> = listOf(
    Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).build(),
    Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).setAccessibilityChannel(1).build(),
)

private const val CAPTION_LARGE_SCALE = 1.3f
private const val CAPTION_WHITE = 0xFFFFFFFF.toInt()
private const val CAPTION_OPAQUE_BLACK = 0xFF000000.toInt()
private const val CAPTION_CLEAR = 0x00000000

/**
 * Points the text selection at CEA-708 service 1, else CEA-608 CC1 (see [CaptionPolicy.preferredTextTrack]).
 * Runs on every track change because ad breaks can drop the 708 track. Only sets the parameters when the
 * override differs, since setting them re-fires the listener.
 */
@OptIn(UnstableApi::class)
internal fun preferCaptionTrack(player: ExoPlayer) {
    val groups = player.currentTracks.groups
    val tracks = ArrayList<TextTrackInfo>()
    groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            if (!group.isTrackSupported(trackIndex)) continue
            val format = group.getTrackFormat(trackIndex)
            tracks += TextTrackInfo(groupIndex, trackIndex, format.sampleMimeType, format.accessibilityChannel)
        }
    }
    val wanted = CaptionPolicy.preferredTextTrack(tracks)?.let { choice ->
        TrackSelectionOverride(groups[choice.groupIndex].mediaTrackGroup, choice.trackIndex)
    }
    val params = player.trackSelectionParameters
    val current = params.overrides.values.filter { it.type == C.TRACK_TYPE_TEXT }
    if (current == listOfNotNull(wanted)) return
    player.trackSelectionParameters = params.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .apply { wanted?.let { addOverride(it) } }
        .build()
}

/** System settings, or large white text on an opaque black box; applied on every change of [style]. */
@OptIn(UnstableApi::class)
internal fun applyCaptionAppearance(subtitles: SubtitleView, style: CaptionStyle, bottomFraction: Float) {
    when (style) {
        CaptionStyle.SYSTEM -> {
            subtitles.setUserDefaultStyle()
            subtitles.setUserDefaultTextSize()
            subtitles.setApplyEmbeddedStyles(true)
        }
        CaptionStyle.LARGE_HIGH_CONTRAST -> {
            // Embedded 608/708 colors would override the box, so they are turned off for this style.
            subtitles.setStyle(
                CaptionStyleCompat(CAPTION_WHITE, CAPTION_OPAQUE_BLACK, CAPTION_CLEAR, CaptionStyleCompat.EDGE_TYPE_NONE, CAPTION_CLEAR, null),
            )
            subtitles.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * CAPTION_LARGE_SCALE)
            subtitles.setApplyEmbeddedStyles(false)
        }
    }
    subtitles.setBottomPaddingFraction(bottomFraction)
}

/** Text tracks are disabled rather than unselected so a zap keeps the user's choice. */
internal fun captionParameters(base: TrackSelectionParameters, enabled: Boolean): TrackSelectionParameters =
    base.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
        .setPreferredTextLanguage(CaptionPolicy.PREFERRED_LANGUAGE)
        .setSelectUndeterminedTextLanguage(true)
        .build()
