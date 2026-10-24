package dev.jamesrich.sofaking.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** CEA-608 CC1 and CEA-708 service 1, declared together so the SEI reader emits both. */
@OptIn(UnstableApi::class)
internal fun captionFormats(): List<Format> = listOf(
    Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).build(),
    Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).setAccessibilityChannel(1).build(),
)

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

/** Text tracks are disabled rather than unselected so a zap keeps the user's choice. */
internal fun captionParameters(base: TrackSelectionParameters, enabled: Boolean): TrackSelectionParameters =
    base.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
        .setPreferredTextLanguage(CaptionPolicy.PREFERRED_LANGUAGE)
        .setSelectUndeterminedTextLanguage(true)
        .build()
