package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.contract.ClientProfile
import dev.jamesrich.sofaking.contract.Wire
import java.nio.file.Path
import java.util.Locale

/** Codecs an MPEG-TS segment carries as they are; anything else is converted for HLS. */
private val TS_VIDEO = setOf("h264", "hevc", "mpeg2video")
private val TS_AUDIO = setOf("aac", "ac3", "eac3", "mp3", "mp2")

/** How long FFmpeg waits for a growing recording to grow before it ends the stream. */
private const val FOLLOW_TIMEOUT_MICROS = 60_000_000L

/** Bits a sample in 8-bit video, which every decoder takes. */
private const val STANDARD_BIT_DEPTH = 8

/**
 * How to play [media] on a client with [profile]: as it is when the client takes its container and codecs, else the
 * streams copied into HLS, else the video copied and the audio converted, else both converted. [audio] is the
 * stream the viewer picked; a pick other than the file's default is never played directly, since the players start
 * on the default and the stream has to carry the pick.
 */
internal fun vodMode(media: MediaInfo?, profile: ClientProfile, audio: AudioStream? = null): String {
    val video = media?.video ?: return Wire.VodMode.TRANSCODE
    val default = media.audio.firstOrNull { it.default } ?: media.audio.firstOrNull()
    val track = audio ?: default
    val videoFits = fits(video, profile)
    val audioFits = track == null || track.codec in profile.audioCodecs
    val direct = videoFits && audioFits && fitsContainer(media, video, profile) && track?.index == default?.index
    val copied = videoFits && video.codec in TS_VIDEO
    return when {
        direct -> Wire.VodMode.DIRECT
        copied && (track == null || (audioFits && track.codec in TS_AUDIO)) -> Wire.VodMode.REMUX
        copied -> Wire.VodMode.AUDIO
        else -> Wire.VodMode.TRANSCODE
    }
}

/** Whether a client with [profile] decodes [video] as it is: its codec, height, HDR and bit depth. */
private fun fits(video: VideoStream, profile: ClientProfile): Boolean =
    video.codec in profile.videoCodecs && (profile.maxHeight?.let { (video.height ?: 0) <= it } ?: true) &&
        (video.transfer == null || profile.hdr) &&
        (!deepColor(video) || video.codec in (profile.deepColor ?: profile.videoCodecs - "h264"))

/** Whether a client with [profile] opens [media]'s container with [video] in it. */
private fun fitsContainer(media: MediaInfo, video: VideoStream, profile: ClientProfile): Boolean {
    val container = containerOf(media.container)
    // HEVC tagged `hev1` in MP4 plays on Android but not in AVPlayer; the remux retags it `hvc1`, which plays on both.
    return (container in profile.containers && !(container == "mp4" && video.codec == "hevc" && video.tag == "hev1")) ||
        (container == "mkv" && "webm" in profile.containers && video.codec in setOf("vp8", "vp9", "av1"))
}

/** Pixel formats with more than 8 bits a sample, such as `yuv420p10le` and `p010le`. */
private val DEEP_PIXEL_FORMAT = Regex("""(p9|p1[0-6]|p01[0-6])(le|be)?$""")

/**
 * True when [video] has more than 8 bits a sample, as HEVC Main 10 and H.264 High 10 do. Most hardware decoders take
 * 10-bit HEVC, VP9 and AV1, but no phone, TV or browser takes 10-bit H.264.
 */
internal fun deepColor(video: VideoStream): Boolean = (video.bitDepth ?: 0) > STANDARD_BIT_DEPTH ||
    video.pixelFormat?.let { DEEP_PIXEL_FORMAT.containsMatchIn(it) } == true

/**
 * True when the stream is written as fragmented MP4 rather than MPEG-TS: copied HEVC, which AVPlayer plays only in
 * fragmented MP4 tagged `hvc1`.
 */
internal fun fragmentedMp4(mode: String, media: MediaInfo?): Boolean =
    (mode == Wire.VodMode.REMUX || mode == Wire.VodMode.AUDIO) && media?.video?.codec == "hevc"

/**
 * The FFmpeg command that writes [file] from [startMs] as an HLS EVENT playlist in [dir], with MPEG-TS segments, or
 * fragmented MP4 for copied HEVC. [encoder] converts the video when the mode does, drawing the picture subtitles
 * [burn] over it. With [follow] it reads a file still being written, ending the stream once the file stops growing
 * for a minute.
 */
internal fun vodCommand(
    ffmpeg: String,
    file: Path,
    dir: Path,
    mode: String,
    startMs: Long,
    media: MediaInfo?,
    audioIndex: Int?,
    follow: Boolean = false,
    encoder: VideoEncoder = VideoEncoder(),
    burn: SubtitleSource? = null,
    /**
     * For a whole playlist, the segment [startMs] begins: FFmpeg numbers its segments from it, offsets their times to
     * the item's, and writes its own playlist aside, as the server lists every segment itself.
     */
    firstSegment: Int? = null,
): List<String> {
    val streams = VodStreams(mode, media, audioIndex, encoder, burn)
    val seek = if (startMs > 0) listOf("-ss", seconds(startMs)) else emptyList()
    return buildList {
        addAll(listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error"))
        if (!streams.copyVideo) addAll(encoder.inputArgs())
        addAll(seek)
        if (follow) addAll(listOf("-follow", "1", "-rw_timeout", "$FOLLOW_TIMEOUT_MICROS"))
        addAll(listOf("-i", file.toString()))
        // A subtitle file is a second input, seeked to the same point.
        burn?.file?.let { addAll(seek + listOf("-i", it.toString())) }
        addAll(streams.args())
        if (firstSegment != null) {
            addAll(listOf("-output_ts_offset", seconds(startMs), "-start_number", "$firstSegment"))
        } else {
            addAll(listOf("-avoid_negative_ts", "make_zero"))
        }
        addAll(hlsOutput(dir, streams.fmp4, whole = firstSegment != null))
    }
}

private fun seconds(ms: Long): String = "%.3f".format(Locale.ROOT, ms.toDouble() / MS_PER_SECOND)

/**
 * The streams [mode] writes from [media]: the video and one audio track ([audioIndex] or the default), copied or
 * converted.
 */
private class VodStreams(
    private val mode: String,
    private val media: MediaInfo?,
    audioIndex: Int?,
    private val encoder: VideoEncoder,
    private val burn: SubtitleSource?,
) {
    val copyVideo = mode == Wire.VodMode.REMUX || mode == Wire.VodMode.AUDIO
    val fmp4 = fragmentedMp4(mode, media)
    private val audio =
        audioIndex ?: media?.audio?.let { tracks -> (tracks.firstOrNull { it.default } ?: tracks.firstOrNull())?.index }

    fun args(): List<String> = buildList {
        val subtitle = burn?.let { if (it.file == null) "0:${it.stream.index}" else "1:s:0" }
        if (subtitle == null) addAll(listOf("-map", "0:v:0?")) else addAll(encoder.overlayGraph(subtitle, media?.video))
        if (audio != null) addAll(listOf("-map", "0:$audio")) else add("-an")
        addAll(listOf("-sn", "-dn"))
        addAll(video(burning = subtitle != null))
        if (audio != null) addAll(audio())
    }

    private fun video(burning: Boolean): List<String> = when {
        copyVideo -> listOf("-c:v", "copy") + if (fmp4) listOf("-tag:v", "hvc1") else emptyList()
        burning -> encoder.codecArgs(keyFrameSeconds = SEGMENT_SECONDS)
        else -> encoder.outputArgs(hdr = media?.video?.transfer != null, keyFrameSeconds = SEGMENT_SECONDS)
    }

    private fun audio(): List<String> =
        if (mode == Wire.VodMode.REMUX) listOf("-c:a", "copy") else listOf("-c:a", "aac", "-ac", "2", "-b:a", "192k")
}

/**
 * The HLS output into [dir], with fragmented MP4 segments when [fmp4]. A [whole] playlist's FFmpeg writes its own
 * playlist aside, as the server lists every segment itself.
 */
private fun hlsOutput(dir: Path, fmp4: Boolean, whole: Boolean): List<String> {
    val segments = if (fmp4) {
        listOf(
            "-hls_segment_type",
            "fmp4",
            "-hls_fmp4_init_filename",
            FMP4_INIT,
            "-hls_segment_filename",
            dir.resolve("seg%d.m4s").toString(),
        )
    } else {
        listOf("-hls_segment_type", "mpegts", "-hls_segment_filename", dir.resolve("seg%d.ts").toString())
    }
    return listOf(
        "-f", "hls", "-hls_time", "$SEGMENT_SECONDS", "-hls_list_size", "0",
        "-hls_playlist_type", "event", "-hls_flags", "temp_file+independent_segments",
    ) + segments + dir.resolve(if (whole) "ffmpeg.m3u8" else "index.m3u8").toString()
}
