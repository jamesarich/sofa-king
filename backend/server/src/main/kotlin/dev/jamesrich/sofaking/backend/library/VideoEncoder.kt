package dev.jamesrich.sofaking.backend.library

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(VideoEncoder::class.java)

/** The GPU encoder `SOFA_HWACCEL` names for converting library files, or [NONE] for libx264 in software. */
enum class HardwareAccel(val setting: String) {
    NONE("none"),
    VAAPI("vaapi"),
    QSV("qsv"),
    NVENC("nvenc");

    companion object {
        fun parse(value: String?): HardwareAccel {
            val wanted = value?.trim()?.lowercase()?.ifEmpty { null } ?: return NONE
            return entries.firstOrNull { it.setting == wanted }
                ?: throw IllegalArgumentException("SOFA_HWACCEL must be one of ${entries.joinToString { it.setting }}")
        }
    }
}

/**
 * How FFmpeg encodes the video of a converted library file: H.264 at up to 1080 lines, a key frame every segment.
 * Decoding and scaling stay in software so any file a GPU cannot decode still converts; [accel] picks the encoder,
 * on the render node [device] for VA-API and Quick Sync. An HDR picture is tone-mapped to SDR first.
 */
class VideoEncoder(val accel: HardwareAccel = HardwareAccel.NONE, val device: String = DEFAULT_DEVICE) {
    /** Options that go before the input: the GPU device the encoder opens. */
    fun inputArgs(): List<String> = when (accel) {
        HardwareAccel.VAAPI -> listOf("-vaapi_device", device)
        HardwareAccel.QSV -> listOf("-qsv_device", device)
        else -> emptyList()
    }

    /** The video filter and encoder options; [hdr] tone-maps the picture to SDR. */
    fun outputArgs(hdr: Boolean, keyFrameSeconds: Int): List<String> {
        val filters = buildList {
            if (hdr) add(TONE_MAP)
            addAll(sizeAndFormat())
        }
        return listOf("-vf", filters.joinToString(",")) + codecArgs(keyFrameSeconds)
    }

    /**
     * The filter graph that draws the picture subtitle stream [subtitle] (an FFmpeg stream specifier such as `0:3`)
     * over the first video stream of input 0, which [video] describes, and maps the result as the output's video;
     * [codecArgs] then encodes it. The subtitle is scaled to the picture's size, as a 1080p PGS track over a 4K picture
     * would otherwise sit in its corner, and an HDR picture is tone-mapped before the subtitle goes on.
     */
    fun overlayGraph(subtitle: String, video: VideoStream?): List<String> {
        val base = if (video?.transfer != null) "[0:v:0]$TONE_MAP,format=yuv420p[base]" else "[0:v:0]null[base]"
        val width = video?.width
        val height = video?.height
        val sub = if (width != null && height != null) "[$subtitle]scale=$width:$height[sub]" else "[$subtitle]null[sub]"
        val rest = (listOf("overlay=eof_action=pass:repeatlast=0") + sizeAndFormat()).joinToString(",")
        return listOf("-filter_complex", "$base;$sub;[base][sub]$rest[v]", "-map", "[v]")
    }

    /** The encoder and its key frames, after [outputArgs]' filter or [overlayGraph]. */
    fun codecArgs(keyFrameSeconds: Int): List<String> {
        val encoder = when (accel) {
            HardwareAccel.NONE -> listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-maxrate", "10M", "-bufsize", "20M")
            HardwareAccel.VAAPI -> listOf("-c:v", "h264_vaapi", "-b:v", "8M", "-maxrate", "10M", "-bufsize", "20M")
            HardwareAccel.QSV -> listOf("-c:v", "h264_qsv", "-preset", "veryfast", "-global_quality", "23", "-forced_idr", "1")
            HardwareAccel.NVENC -> listOf("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", "23", "-b:v", "0", "-maxrate", "10M", "-bufsize", "20M", "-forced-idr", "1")
        }
        return encoder + listOf("-force_key_frames", "expr:gte(t,n_forced*$keyFrameSeconds)")
    }

    /** At most 1080 lines, in the pixel format the encoder takes. */
    private fun sizeAndFormat(): List<String> = buildList {
        add("scale=-2:'min(1080,ih)'")
        when (accel) {
            HardwareAccel.NONE, HardwareAccel.NVENC -> add("format=yuv420p")
            HardwareAccel.QSV -> add("format=nv12")
            HardwareAccel.VAAPI -> addAll(listOf("format=nv12", "hwupload"))
        }
    }

    /**
     * This encoder when [ffmpeg] can encode a frame with it, else software, so a missing driver or device costs speed
     * rather than playback. Software needs no check.
     */
    suspend fun verified(ffmpeg: String, run: (List<String>) -> Boolean = ::succeeds): VideoEncoder {
        if (accel == HardwareAccel.NONE) return this
        val command = listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error") + inputArgs() +
            listOf("-f", "lavfi", "-i", "color=black:s=320x240:d=1", "-frames:v", "1") + outputArgs(hdr = false, keyFrameSeconds = 4) + listOf("-f", "null", "-")
        val works = withContext(Dispatchers.IO) { run(command) }
        if (works) {
            log.info("vod encoder ready accel={}", accel.setting)
            return this
        }
        log.warn("vod encoder unavailable accel={}; converting in software", accel.setting)
        return VideoEncoder(HardwareAccel.NONE, device)
    }

    companion object {
        /** The first render node, where Intel and AMD GPUs show up in a container. */
        const val DEFAULT_DEVICE = "/dev/dri/renderD128"

        /** HDR10 or HLG to SDR BT.709 with zimg and the Hable curve, as most media servers do. */
        private const val TONE_MAP = "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv"

        private fun succeeds(command: List<String>): Boolean = try {
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: IOException) {
            false
        }
    }
}
