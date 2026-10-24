package dev.jamesrich.sofaking.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import dev.jamesrich.sofaking.data.PlaybackProfile

/**
 * What this device plays, from the decoders Media3 itself chooses among (`MediaCodecUtil`, which leaves out the ones
 * it knows to be broken) and the display's HDR types.
 */
@OptIn(UnstableApi::class)
internal fun media3Profile(context: Context): PlaybackProfile {
    fun decoders(mime: String): List<MediaCodecInfo> = try {
        MediaCodecUtil.getDecoderInfos(
            mime,
            false, // secure
            false, // tunneling
        )
    } catch (_: MediaCodecUtil.DecoderQueryException) {
        emptyList()
    }
    return playbackProfileOf(
        decodes = { decoders(it).isNotEmpty() },
        decoderMaxHeight = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
            .flatMap(::decoders)
            .mapNotNull { it.capabilities?.videoCapabilities?.supportedHeights?.upper }
            .maxOrNull(),
        hevcHdr10 = decoders(MimeTypes.VIDEO_H265).any { info ->
            info.profileLevels.any {
                it.profile in
                    HEVC_HDR10_PROFILES
            }
        },
        displayHdrTypes = displayHdrTypes(context),
        decodesDeep = { mime ->
            decoders(mime).any { info ->
                info.profileLevels.any {
                    it.profile in
                        DEEP_PROFILES[mime].orEmpty()
                }
            }
        },
    )
}

/**
 * Media3's containers with the codecs [decodes] finds a decoder for, the tallest picture the H.264 or HEVC decoders
 * take (at most 2160) and HDR when an HEVC decoder takes HDR10's profile and the display shows HDR10. A display that
 * lists only Dolby Vision or HLG does not count, since most HDR files are HDR10. [decodesDeep] tells which of those
 * codecs a decoder also takes at 10 bits a sample: an emulator's or an older box's HEVC decoder may take Main only.
 */
internal fun playbackProfileOf(
    decodes: (mime: String) -> Boolean,
    decoderMaxHeight: Int?,
    hevcHdr10: Boolean,
    displayHdrTypes: IntArray,
    decodesDeep: (mime: String) -> Boolean,
): PlaybackProfile {
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
    val hdr10Display = displayHdrTypes.any {
        it == Display.HdrCapabilities.HDR_TYPE_HDR10 || it == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
    }
    return PlaybackProfile(
        containers = listOf("mp4", "mkv", "webm", "ts"),
        videoCodecs = video,
        audioCodecs = audio,
        maxHeight = decoderMaxHeight?.coerceAtMost(UHD_LINES) ?: FULL_HD_LINES,
        hdr = hevcHdr10 && hdr10Display,
        deepColor = DEEP_PROFILES.filterKeys { decodes(it) && decodesDeep(it) }.keys.map(CODEC_NAMES::getValue),
    )
}

private val HEVC_HDR10_PROFILES =
    setOf(CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus)

/** The profiles that take 10 bits a sample, by codec. */
private val DEEP_PROFILES = mapOf(
    MimeTypes.VIDEO_H264 to setOf(CodecProfileLevel.AVCProfileHigh10),
    MimeTypes.VIDEO_H265 to setOf(CodecProfileLevel.HEVCProfileMain10) + HEVC_HDR10_PROFILES,
    MimeTypes.VIDEO_VP9 to
        setOf(CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus),
    MimeTypes.VIDEO_AV1 to
        setOf(
            CodecProfileLevel.AV1ProfileMain10,
            CodecProfileLevel.AV1ProfileMain10HDR10,
            CodecProfileLevel.AV1ProfileMain10HDR10Plus,
        ),
)

private val CODEC_NAMES = mapOf(
    MimeTypes.VIDEO_H264 to "h264",
    MimeTypes.VIDEO_H265 to "hevc",
    MimeTypes.VIDEO_VP9 to "vp9",
    MimeTypes.VIDEO_AV1 to "av1",
)

/** The HDR types the default display shows in its current mode; Android 14 reports them per mode. */
private fun displayHdrTypes(context: Context): IntArray {
    val display =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return IntArray(0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return display.mode.supportedHdrTypes
    @Suppress("DEPRECATION")
    val types = display.hdrCapabilities?.supportedHdrTypes
    return types ?: IntArray(0)
}

/** The tallest picture asked for, 2160-line 4K, and the height a decoder that does not say is held to. */
private const val UHD_LINES = 2160
private const val FULL_HD_LINES = 1080
