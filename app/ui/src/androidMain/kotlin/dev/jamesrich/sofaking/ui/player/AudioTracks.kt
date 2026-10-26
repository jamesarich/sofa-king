package dev.jamesrich.sofaking.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

/**
 * The stream's audio tracks as the stream card lists them; a track's id is its group and index, which
 * [selectAudioTrack] reads back.
 */
internal fun audioTracksOf(tracks: Tracks): List<AudioTrack> = buildList {
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            if (!group.isTrackSupported(trackIndex)) continue
            add(
                AudioTrack(
                    "$groupIndex:$trackIndex",
                    group.getTrackFormat(trackIndex).audioLabel(size + 1),
                    group.isTrackSelected(trackIndex),
                ),
            )
        }
    }
}

/** The stream's own label, else its language spelled out, else a number. */
private fun Format.audioLabel(ordinal: Int): String {
    val language = language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
    return label?.takeIf { it.isNotBlank() }
        ?: language?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { null } ?: it }
        ?: "Track $ordinal"
}

/**
 * Points the audio selection at the track [id] from [audioTracksOf]; an id that no longer matches a group is ignored.
 */
@OptIn(UnstableApi::class)
internal fun selectAudioTrack(player: ExoPlayer, id: String) {
    val (groupIndex, trackIndex) = id.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return
    val group =
        player.currentTracks.groups.getOrNull(groupIndex)?.takeIf {
            it.type == C.TRACK_TYPE_AUDIO && trackIndex < it.length
        }
            ?: return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        .build()
}

/** Codec, picture size, frame rate and bitrate from the formats the player has chosen. */
internal fun streamInfoOf(video: Format?, audio: Format?): StreamInfo = StreamInfo(
    videoCodec = video?.let { codecName(it.codecs, it.sampleMimeType) },
    width = video?.width?.takeIf { it != Format.NO_VALUE },
    height = video?.height?.takeIf { it != Format.NO_VALUE },
    frameRate = video?.frameRate?.takeIf { it != Format.NO_VALUE.toFloat() },
    bitrateBps = video?.let {
        listOf(it.averageBitrate, it.peakBitrate, it.bitrate).firstOrNull { b -> b != Format.NO_VALUE }
    },
    audioCodec = audio?.let { codecName(it.codecs, it.sampleMimeType) },
)

/** `avc1.64001f` reads as H.264; a bare MIME type reads by its subtype. */
internal fun codecName(codecs: String?, mimeType: String?): String? {
    val codec = codecs?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
    val mime = codec?.let { MimeTypes.getMediaMimeType(it) } ?: mimeType
    return when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_OPUS -> "Opus"
        null -> codec
        else -> mime.substringAfter('/').uppercase()
    }
}

/**
 * The audio output an [encoding] (one of Media3's `C.ENCODING_*`) and Android [channelConfig] mask describe: linear PCM
 * was decoded here, anything else passes through encoded.
 */
@OptIn(UnstableApi::class)
internal fun audioOutputOf(encoding: Int, channelConfig: Int): AudioOutput = if (Util.isEncodingLinearPcm(encoding)) {
    AudioOutput.Decoded(Integer.bitCount(channelConfig))
} else {
    AudioOutput.PassedThrough(
        when (encoding) {
            C.ENCODING_AC3 -> "AC-3"
            C.ENCODING_E_AC3 -> "E-AC-3"
            C.ENCODING_E_AC3_JOC -> "E-AC-3 Atmos"
            C.ENCODING_AC4 -> "AC-4"
            C.ENCODING_DTS -> "DTS"
            C.ENCODING_DTS_HD -> "DTS-HD"
            C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
            C.ENCODING_MP3 -> "MP3"
            C.ENCODING_AAC_LC, C.ENCODING_AAC_HE_V1, C.ENCODING_AAC_HE_V2 -> "AAC"
            C.ENCODING_OPUS -> "Opus"
            else -> null
        },
    )
}
