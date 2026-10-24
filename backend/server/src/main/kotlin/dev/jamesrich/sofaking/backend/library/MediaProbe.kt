package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.io.runFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** What FFprobe found in a file: its container, length and the streams a player chooses between. */
@Serializable
data class MediaInfo(
    /** FFprobe's format names, such as `matroska,webm` or `mov,mp4,m4a,3gp,3g2,mj2`. */
    val container: String,
    val durationMs: Long? = null,
    val bitrate: Long? = null,
    val video: VideoStream? = null,
    val audio: List<AudioStream> = emptyList(),
    val subtitles: List<SubtitleStream> = emptyList(),
)

@Serializable
data class VideoStream(
    val codec: String,
    val profile: String? = null,
    val level: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val pixelFormat: String? = null,
    val frameRate: Double? = null,
    val bitDepth: Int? = null,
    /** `smpte2084` (HDR10) or `arib-std-b67` (HLG) when the stream is HDR; null for SDR. */
    val transfer: String? = null,
    /** The codec tag, such as `hvc1` or `hev1` for HEVC in MP4; null when the file has none. */
    val tag: String? = null,
)

@Serializable
data class AudioStream(
    val index: Int,
    val codec: String,
    val channels: Int? = null,
    val language: String? = null,
    val title: String? = null,
    val default: Boolean = false,
)

@Serializable
data class SubtitleStream(
    val index: Int,
    val codec: String,
    val language: String? = null,
    val title: String? = null,
    val default: Boolean = false,
    val forced: Boolean = false,
)

private val probeJson = Json { ignoreUnknownKeys = true }

/** FFprobe beside [ffmpeg]: the same directory when it is a path, otherwise `ffprobe` from the search path. */
internal fun ffprobeBeside(ffmpeg: String): String {
    val path = Path.of(ffmpeg)
    return if (path.parent == null) {
        "ffprobe"
    } else {
        path.resolveSibling(path.fileName.toString().replace("ffmpeg", "ffprobe")).toString()
    }
}

/** Runs FFprobe on local files; one probe reads headers only and gives up after [timeoutSeconds]. */
class MediaProbe(private val ffprobe: String, private val timeoutSeconds: Int = 30) {
    /** What [file] holds, or null when FFprobe fails, times out or finds no video or audio. */
    suspend fun probe(file: Path): MediaInfo? = withContext(Dispatchers.IO) {
        val output = Files.createTempFile("sofa-ffprobe-", ".json")
        try {
            val command =
                listOf(
                    ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", "--",
                    file.toString(),
                )
            val exit = ProcessBuilder(
                command,
            ).redirectOutput(
                output.toFile(),
            ).redirectError(ProcessBuilder.Redirect.DISCARD).runFor(timeoutSeconds.seconds)
            if (exit != 0) null else parseProbe(Files.readString(output))
        } catch (_: java.io.IOException) {
            null
        } finally {
            Files.deleteIfExists(output)
        }
    }
}

/** FFprobe's `-show_format -show_streams` JSON as a [MediaInfo]; null when it holds no video or audio stream. */
internal fun parseProbe(text: String): MediaInfo? {
    val root = runCatching { probeJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val format = root["format"] as? JsonObject
    val streams = (root["streams"]?.jsonArray ?: return null).mapNotNull { it as? JsonObject }
    fun JsonObject.str(key: String) =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.ifEmpty { null }
    fun JsonObject.int(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
        it.intOrNull
            ?: it.contentOrNull?.toIntOrNull()
    }
    fun JsonObject.tag(key: String) = (this["tags"] as? JsonObject)?.let { tags ->
        tags[key] ?: tags[key.uppercase()]
    }?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
    fun JsonObject.disposition(key: String) = (this["disposition"] as? JsonObject)?.int(key) == 1
    val videoStream = streams.firstOrNull { it.str("codec_type") == "video" && !it.disposition("attached_pic") }
    val video = videoStream?.let { s ->
        VideoStream(
            codec = s.str("codec_name") ?: "unknown",
            profile = s.str("profile"),
            level = s.int("level")?.takeIf { it > 0 },
            width = s.int("width"),
            height = s.int("height"),
            pixelFormat = s.str("pix_fmt"),
            frameRate = s.str("avg_frame_rate")?.let(::rate) ?: s.str("r_frame_rate")?.let(::rate),
            bitDepth = s.int("bits_per_raw_sample"),
            transfer = s.str("color_transfer")?.takeIf { it == "smpte2084" || it == "arib-std-b67" },
            tag = s.str("codec_tag_string")?.takeUnless { it.startsWith("[") },
        )
    }
    val audio = streams.filter { it.str("codec_type") == "audio" }.map { s ->
        AudioStream(
            s.int("index") ?: 0,
            s.str("codec_name") ?: "unknown",
            s.int("channels"),
            s.tag("language"),
            s.tag("title"),
            s.disposition("default"),
        )
    }
    if (video == null && audio.isEmpty()) return null
    val subtitles = streams.filter { it.str("codec_type") == "subtitle" }.map { s ->
        SubtitleStream(
            s.int("index") ?: 0,
            s.str("codec_name") ?: "unknown",
            s.tag("language"),
            s.tag("title"),
            s.disposition("default"),
            s.disposition("forced"),
        )
    }
    return MediaInfo(
        container = format?.str("format_name") ?: "unknown",
        durationMs = format?.str("duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() },
        bitrate = format?.str("bit_rate")?.toLongOrNull(),
        video = video,
        audio = audio,
        subtitles = subtitles,
    )
}

/** `24000/1001` as 23.976; null for `0/0`. */
private fun rate(text: String): Double? {
    val (n, d) = text.split('/').takeIf { it.size == 2 }?.map { it.toDoubleOrNull() ?: return null }
        ?: return text.toDoubleOrNull()
    return if (d == 0.0 || n == 0.0) null else n / d
}
