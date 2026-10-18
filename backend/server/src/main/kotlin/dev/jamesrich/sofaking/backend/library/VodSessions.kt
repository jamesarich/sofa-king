package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.contract.ClientProfile
import dev.jamesrich.sofaking.contract.OpenVodPlayback
import dev.jamesrich.sofaking.contract.PlaybackDescriptor
import dev.jamesrich.sofaking.contract.UpdateVodPlayback
import dev.jamesrich.sofaking.contract.VodSession
import dev.jamesrich.sofaking.contract.Wire
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(VodSessions::class.java)

/** The HLS segment length FFmpeg cuts to, in seconds. */
private const val SEGMENT_SECONDS = 4

/** Codecs an MPEG-TS segment carries as they are; anything else is converted for HLS. */
private val TS_VIDEO = setOf("h264", "hevc", "mpeg2video")
private val TS_AUDIO = setOf("aac", "ac3", "eac3", "mp3", "mp2")

/** How long FFmpeg waits for a growing recording to grow before it ends the stream. */
private const val FOLLOW_TIMEOUT_MICROS = 60_000_000L

/**
 * A recording's file as [VodSessions] plays it. [growing] is true while it records, so its stream follows the file;
 * [durationMs] is then the length it will have.
 */
class PlayableFile(val file: Path, val durationMs: Long?, val growing: Boolean)

/**
 * One library file or recording being played; [process] is the FFmpeg writing its HLS stream, null for direct play.
 * A recording's [file] and [growing] change when it finishes, as the file moves into place.
 */
internal class VodState(
    val id: String,
    val itemId: String,
    val recordingId: String?,
    var file: Path,
    var growing: Boolean,
    val mode: String,
    val durationMs: Long?,
    val audioIndex: Int?,
    val media: MediaInfo?,
    val secret: String,
    var epoch: Int,
    var startMs: Long,
    var expires: Instant,
    var process: Process? = null,
)

/** A file to serve for a VOD media request, and its type. */
class VodMedia(val file: Path, val contentType: String)

/**
 * Plays library files. A file the client's [ClientProfile] can play is served as it is, with byte ranges; otherwise
 * FFmpeg writes it as HLS into [directory], copying what the client can play and converting the rest, at most
 * [maxConverting] files at once. A session lives while the client renews it or fetches its media. [recordings] finds a
 * recording's file and [probe] reads it; without them only library items play.
 */
class VodSessions(
    private val catalog: LibraryCatalog,
    private val clock: Clock,
    private val ffmpeg: String,
    private val directory: Path,
    private val maxConverting: Int = 2,
    private val lease: Duration = Duration.ofMinutes(5),
    private val recordings: (suspend (String) -> PlayableFile?)? = null,
    private val probe: suspend (Path) -> MediaInfo? = { null },
) {
    private val sessions = HashMap<String, VodState>()
    private val mutex = Mutex()
    private val random = SecureRandom()

    /** Clears what an earlier run left behind. */
    suspend fun start() = withContext(Dispatchers.IO) { clear(directory) }

    suspend fun open(request: OpenVodPlayback): VodSession {
        if (request.startMs < 0) throw ApiFailure(400, "INVALID_REQUEST", "startMs is not negative")
        request.recordingId?.let { return openRecording(it, request) }
        val snapshot = catalog.snapshot()
        val view = snapshot.views[request.itemId] ?: throw ApiFailure(404, "ITEM_NOT_FOUND", "No such item")
        val relative = view.record.path?.takeIf { view.record.kind != ItemKind.SHOW && view.record.kind != ItemKind.SEASON }
            ?: throw ApiFailure(400, "INVALID_REQUEST", "Only a movie, episode or video plays")
        val library = snapshot.libraries.firstOrNull { it.id == view.record.libraryId } ?: throw ApiFailure(404, "ITEM_NOT_FOUND", "No such item")
        val root = Path.of(library.path).normalize()
        val file = root.resolve(relative).normalize()
        if (!file.startsWith(root) || withContext(Dispatchers.IO) { !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }) {
            throw ApiFailure(404, "ITEM_MISSING", "The file is not there; is its disk mounted?")
        }
        return start(request, view.record.id, null, file, growing = false, view.record.media, view.item.durationMs)
    }

    /** Plays a recording; one still recording is never played directly, as its stream has to follow the file. */
    private suspend fun openRecording(id: String, request: OpenVodPlayback): VodSession {
        val playable = recordings?.invoke(id) ?: throw ApiFailure(404, "RECORDING_NOT_FOUND", "No such recording, or nothing recorded yet")
        val size = withContext(Dispatchers.IO) { if (Files.isRegularFile(playable.file, LinkOption.NOFOLLOW_LINKS)) Files.size(playable.file) else -1L }
        if (size < 0) throw ApiFailure(404, "RECORDING_MISSING", "The recording's file is not there; is its disk mounted?")
        if (size == 0L) throw ApiFailure(409, "RECORDING_NOT_READY", "Nothing has been recorded yet; try again in a few seconds", true)
        val media = probe(playable.file)
        return start(request, "", id, playable.file, playable.growing, media, playable.durationMs ?: media?.durationMs)
    }

    private suspend fun start(request: OpenVodPlayback, itemId: String, recordingId: String?, file: Path, growing: Boolean, media: MediaInfo?, durationMs: Long?): VodSession {
        val audio = request.audioIndex?.let { index -> media?.audio?.firstOrNull { it.index == index } ?: throw ApiFailure(400, "INVALID_REQUEST", "No audio stream $index") }
        val mode = vodMode(media, if (growing) request.profile.copy(containers = emptyList()) else request.profile, audio)
        return mutex.withLock {
            reap()
            if (mode != Wire.VodMode.DIRECT && sessions.values.count { it.mode != Wire.VodMode.DIRECT } >= maxConverting) {
                throw ApiFailure(503, Wire.ErrorCode.CAPACITY_EXHAUSTED, "Too many files are being converted; try again when one stops", true)
            }
            val state = VodState(
                UUID.randomUUID().toString(), itemId, recordingId, file, growing, mode, durationMs, audio?.index, media, secret(), epoch = 1,
                startMs = if (mode == Wire.VodMode.DIRECT) 0 else request.startMs, expires = clock.instant().plus(lease),
            )
            if (mode != Wire.VodMode.DIRECT) launch(state)
            sessions[state.id] = state
            state.wire()
        }
    }

    suspend fun get(id: String): VodSession = mutex.withLock { live(id).wire() }

    /** Renews [id]'s lease; with a start, restarts a converted stream there under a new epoch. */
    suspend fun update(id: String, update: UpdateVodPlayback): VodSession {
        val start = update.startMs
        if (start != null && start < 0) throw ApiFailure(400, "INVALID_REQUEST", "startMs is not negative")
        // A recording may have finished and moved since the stream started, so a restart finds its file again.
        val recordingId = mutex.withLock { live(id).recordingId }
        val playable = if (start != null && recordingId != null) recordings?.invoke(recordingId) else null
        return mutex.withLock { restart(live(id), start, playable) }
    }

    private fun restart(state: VodState, start: Long?, playable: PlayableFile?): VodSession {
        state.expires = clock.instant().plus(lease)
        if (start != null && state.mode != Wire.VodMode.DIRECT) {
            playable?.let { state.file = it.file; state.growing = it.growing }
            stop(state)
            state.epoch++
            state.startMs = start
            launch(state)
        }
        return state.wire()
    }

    suspend fun release(id: String): Boolean = mutex.withLock { sessions.remove(id)?.also { stop(it) } != null }

    suspend fun expire() = mutex.withLock { reap() }

    /** Stops every session, as the server shuts down. */
    suspend fun close() = mutex.withLock {
        sessions.values.forEach { stop(it) }
        sessions.clear()
    }

    /** The file behind a media URL, waiting a little for FFmpeg to write it; null when the URL is stale or unknown. */
    suspend fun media(id: String, epoch: Int, secret: String, name: String): VodMedia? {
        val state = mutex.withLock {
            val state = sessions[id]?.takeIf { it.epoch == epoch && sameSecret(it.secret, secret) } ?: return null
            state.expires = clock.instant().plus(lease)
            state
        }
        if (state.mode == Wire.VodMode.DIRECT) {
            return if (name == "file.${extension(state.media)}") VodMedia(state.file, directType(state.media)) else null
        }
        val dir = directory.resolve(state.id)
        return when {
            name == "index.m3u8" -> waitFor(state, epoch) { Files.isRegularFile(dir.resolve(name)) && Files.isRegularFile(dir.resolve("seg0.ts")) }
                ?.let { VodMedia(dir.resolve(name), "application/vnd.apple.mpegurl") }
            SEGMENT.matches(name) -> waitFor(state, epoch) { Files.isRegularFile(dir.resolve(name)) }?.let { VodMedia(dir.resolve(name), "video/mp2t") }
            else -> null
        }
    }

    private suspend fun waitFor(state: VodState, epoch: Int, ready: () -> Boolean): Unit? {
        repeat(200) {
            if (withContext(Dispatchers.IO) { ready() }) return Unit
            val running = mutex.withLock { state.epoch == epoch && state.process?.isAlive == true && sessions[state.id] === state }
            if (!running) return if (withContext(Dispatchers.IO) { ready() }) Unit else null
            delay(100)
        }
        return null
    }

    private fun live(id: String): VodState {
        reap()
        return sessions[id] ?: throw ApiFailure(410, Wire.ErrorCode.SESSION_EXPIRED, "Session was released or expired")
    }

    private fun reap() {
        val now = clock.instant()
        sessions.values.filter { it.expires <= now }.forEach { sessions.remove(it.id); stop(it) }
    }

    private fun launch(state: VodState) {
        val dir = directory.resolve(state.id)
        clear(dir)
        Files.createDirectories(dir)
        val command = vodCommand(ffmpeg, state.file, dir, state.mode, state.startMs, state.media, state.audioIndex, follow = state.growing)
        state.process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("ffmpeg.log").toFile()).start()
        } catch (e: IOException) {
            log.warn("vod ffmpeg did not start class={}", e.javaClass.simpleName)
            throw ApiFailure(503, "TRANSCODER_UNAVAILABLE", "FFmpeg could not start", true)
        }
        log.info("vod session started session={} mode={} startMs={}", state.id, state.mode, state.startMs)
    }

    private fun stop(state: VodState) {
        state.process?.let { process ->
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        state.process = null
        clear(directory.resolve(state.id))
    }

    private fun VodState.wire(): VodSession {
        val base = "/media/vod/$id/$epoch/$secret"
        val descriptor = if (mode == Wire.VodMode.DIRECT) PlaybackDescriptor("file", directType(media), "$base/file.${extension(media)}", epoch)
        else PlaybackDescriptor("hls", "application/vnd.apple.mpegurl", "$base/index.m3u8", epoch)
        return VodSession(id, itemId, mode, descriptor, startMs, durationMs, expires.toString(), recordingId)
    }

    private fun secret(): String = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))

    private companion object {
        val SEGMENT = Regex("seg[0-9]{1,6}\\.ts")

        fun sameSecret(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        fun clear(dir: Path) {
            if (!Files.isDirectory(dir)) return
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }
}

/** The container family a client names for FFprobe's format names. */
internal fun containerOf(format: String?): String = when {
    format == null -> ""
    format.startsWith("matroska") -> "mkv"
    format.startsWith("mov,mp4") -> "mp4"
    format == "mpegts" -> "ts"
    else -> format.substringBefore(',')
}

private fun extension(media: MediaInfo?): String = containerOf(media?.container).ifEmpty { "bin" }

private fun directType(media: MediaInfo?): String = when (containerOf(media?.container)) {
    "mkv" -> "video/x-matroska"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "ts" -> "video/mp2t"
    "avi" -> "video/x-msvideo"
    else -> "application/octet-stream"
}

/**
 * How to play [media] on a client with [profile]: as it is when the client takes its container and codecs, else the
 * streams copied into HLS, else the video copied and the audio converted, else both converted. [audio] is the
 * stream the viewer picked; direct play leaves the choice to the player.
 */
internal fun vodMode(media: MediaInfo?, profile: ClientProfile, audio: AudioStream? = null): String {
    val video = media?.video ?: return Wire.VodMode.TRANSCODE
    val track = audio ?: media.audio.firstOrNull { it.default } ?: media.audio.firstOrNull()
    val videoFits = video.codec in profile.videoCodecs &&
        (profile.maxHeight?.let { (video.height ?: 0) <= it } ?: true) && (video.transfer == null || profile.hdr)
    val audioFits = track == null || track.codec in profile.audioCodecs
    val container = containerOf(media.container)
    val containerFits = container in profile.containers || (container == "mkv" && "webm" in profile.containers && video.codec in setOf("vp8", "vp9", "av1"))
    return when {
        videoFits && audioFits && containerFits -> Wire.VodMode.DIRECT
        videoFits && video.codec in TS_VIDEO && (track == null || (audioFits && track.codec in TS_AUDIO)) -> Wire.VodMode.REMUX
        videoFits && video.codec in TS_VIDEO -> Wire.VodMode.AUDIO
        else -> Wire.VodMode.TRANSCODE
    }
}

/**
 * The FFmpeg command that writes [file] from [startMs] as an HLS EVENT playlist and MPEG-TS segments in [dir]. With
 * [follow] it reads a file still being written, ending the stream once the file stops growing for a minute.
 */
internal fun vodCommand(ffmpeg: String, file: Path, dir: Path, mode: String, startMs: Long, media: MediaInfo?, audioIndex: Int?, follow: Boolean = false): List<String> {
    val audio = audioIndex ?: media?.audio?.let { tracks -> (tracks.firstOrNull { it.default } ?: tracks.firstOrNull())?.index }
    val copyVideo = mode == Wire.VodMode.REMUX || mode == Wire.VodMode.AUDIO
    val copyAudio = mode == Wire.VodMode.REMUX
    return buildList {
        addAll(listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error"))
        if (startMs > 0) addAll(listOf("-ss", "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0)))
        if (follow) addAll(listOf("-follow", "1", "-rw_timeout", "$FOLLOW_TIMEOUT_MICROS"))
        addAll(listOf("-i", file.toString(), "-map", "0:v:0?"))
        if (audio != null) addAll(listOf("-map", "0:$audio")) else add("-an")
        addAll(listOf("-sn", "-dn"))
        if (copyVideo) addAll(listOf("-c:v", "copy"))
        else addAll(listOf(
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-maxrate", "10M", "-bufsize", "20M", "-pix_fmt", "yuv420p",
            "-vf", "scale=-2:'min(1080,ih)'", "-force_key_frames", "expr:gte(t,n_forced*$SEGMENT_SECONDS)",
        ))
        if (audio != null) {
            if (copyAudio) addAll(listOf("-c:a", "copy")) else addAll(listOf("-c:a", "aac", "-ac", "2", "-b:a", "192k"))
        }
        addAll(listOf(
            "-avoid_negative_ts", "make_zero", "-f", "hls", "-hls_time", "$SEGMENT_SECONDS", "-hls_list_size", "0",
            "-hls_playlist_type", "event", "-hls_flags", "temp_file+independent_segments", "-hls_segment_type", "mpegts",
            "-hls_segment_filename", dir.resolve("seg%d.ts").toString(), dir.resolve("index.m3u8").toString(),
        ))
    }
}
