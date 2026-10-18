package dev.jamesrich.sofaking.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.view.Display
import androidx.media3.common.MimeTypes
import dev.jamesrich.sofaking.data.PlaybackProfile

/**
 * Media3's containers with the codecs this device has decoders for, the tallest picture its H.264 or HEVC
 * decoder takes (at most 2160) and HDR when the display shows it and HEVC decodes.
 */
internal fun media3Profile(context: Context): PlaybackProfile {
    val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
    fun decoding(mime: String) = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
    fun decodes(mime: String) = decoding(mime).isNotEmpty()

    val video = buildList {
        add("h264")
        if (decodes(MimeTypes.VIDEO_H265)) add("hevc")
        if (decodes(MimeTypes.VIDEO_VP9)) add("vp9")
        if (decodes(MimeTypes.VIDEO_AV1)) add("av1")
        if (decodes(MimeTypes.VIDEO_MPEG2)) add("mpeg2video")
    }
    // Media3 decodes these in software everywhere; Dolby audio needs the device's decoder.
    val audio = buildList {
        addAll(listOf("aac", "mp3", "opus", "vorbis", "flac"))
        if (decodes(MimeTypes.AUDIO_AC3)) add("ac3")
        if (decodes(MimeTypes.AUDIO_E_AC3)) add("eac3")
    }
    val height = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
        .flatMap { mime -> decoding(mime).mapNotNull { it.maxHeight(mime) } }
        .maxOrNull()?.coerceAtMost(2160) ?: 1080
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    @Suppress("DEPRECATION")
    val hdrDisplay = display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    return PlaybackProfile(
        containers = listOf("mp4", "mkv", "webm", "ts"),
        videoCodecs = video,
        audioCodecs = audio,
        maxHeight = height,
        hdr = hdrDisplay && "hevc" in video,
    )
}

private fun MediaCodecInfo.maxHeight(mime: String): Int? = try {
    getCapabilitiesForType(mime).videoCapabilities?.supportedHeights?.upper
} catch (_: IllegalArgumentException) {
    null
}
