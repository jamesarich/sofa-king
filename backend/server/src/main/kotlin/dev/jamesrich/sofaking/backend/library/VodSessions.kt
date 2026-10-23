package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.io.runFor
import dev.jamesrich.sofaking.backend.io.terminate
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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(VodSessions::class.java)

/** The HLS segment length FFmpeg cuts to, in seconds. */
private const val SEGMENT_SECONDS = 4
private const val SEGMENT_MS = SEGMENT_SECONDS * 1000L

/**
 * How many segments a whole playlist of an item [durationMs] long lists. Less than a second left over joins the last
 * segment, as the stream's last frames can end that much before its audio does.
 */
internal fun segmentCount(durationMs: Long): Int = maxOf(1, ((durationMs + SEGMENT_MS - 1000) / SEGMENT_MS).toInt())

/** A VOD playlist of every segment of an item [durationMs] long, the last one as long as what is left. */
internal fun wholePlaylist(durationMs: Long): String = buildString {
    append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:${SEGMENT_SECONDS + 1}\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
    val count = segmentCount(durationMs)
    for (i in 0 until count) {
        val length = if (i == count - 1) durationMs - i * SEGMENT_MS else SEGMENT_MS
        append("#EXTINF:%.3f,\nseg$i.ts\n".format(java.util.Locale.ROOT, length / 1000.0))
    }
    append("#EXT-X-ENDLIST\n")
}

/** The init segment of a fragmented MP4 stream. */
private const val FMP4_INIT = "init.mp4"

/** Codecs an MPEG-TS segment carries as they are; anything else is converted for HLS. */
private val TS_VIDEO = setOf("h264", "hevc", "mpeg2video")
private val TS_AUDIO = setOf("aac", "ac3", "eac3", "mp3", "mp2")

/** Subtitle codecs FFmpeg converts to WebVTT; the others are pictures. */
private val TEXT_SUBTITLES = setOf("subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text")

/**
 * A subtitle track to read or draw: stream [index] of the item's own file when [file] is null, else the subtitle
 * file [file] beside it.
 */
internal class SubtitleSource(val stream: SubtitleStream, val file: Path?) {
    val picture: Boolean get() = stream.codec !in TEXT_SUBTITLES
}

/** The lock on one subtitle file, and how many requests hold or wait for it. */
private class ExtractionLock {
    val mutex = Mutex()
    var users = 0
}

/** Where extracted subtitles are kept, under the conversion directory. */
private const val SUBTITLES = "subtitles"

/** How long reading one subtitle stream out of a file may take. */
private const val SUBTITLE_TIMEOUT_MINUTES = 10L

/** Subtitle extractions that run at once. */
private const val SUBTITLE_EXTRACTIONS = 3

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
    /** The picture subtitle drawn into the picture, if one is. */
    val burn: SubtitleSource?,
    val media: MediaInfo?,
    val secret: String,
    var epoch: Int,
    var startMs: Long,
    var expires: Instant,
    val startedAt: Instant,
    /** Set off the sessions' lock while [restarting] is held, and read under it. */
    @Volatile var process: Process? = null,
    /**
     * True when the session serves a playlist of the whole item and converts from the segment a player asks for;
     * [runStart] is the segment the running FFmpeg started at.
     */
    val whole: Boolean = false,
    var runStart: Int = 0,
) {
    /** Held while this session's FFmpeg is stopped and started again, off the sessions' lock. */
    val restarting = Mutex()
}

/** A library file or recording being played, as the portal's Watching now lists it. */
class VodActivity(val id: String, val itemId: String, val recordingId: String?, val mode: String, val startedAt: Instant, val expiresAt: Instant)

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
    private var encoder: VideoEncoder = VideoEncoder(),
) {
    private val sessions = HashMap<String, VodState>()
    private val mutex = Mutex()
    private val random = SecureRandom()
    /** A lock per subtitle file while anyone wants it, so one file is never extracted twice at once and a slow one holds up no other. */
    private val extracting = HashMap<String, ExtractionLock>()
    /** Each extraction reads a whole file, so only a few run at once. */
    private val extractions = Semaphore(SUBTITLE_EXTRACTIONS)
    /** Sessions taken out under the lock whose FFmpeg and files [dispose] deals with once it is released. */
    private val retired = ConcurrentLinkedQueue<VodState>()

    /**
     * Runs [action] under the sessions' lock, then ends the FFmpeg of every session it retired and deletes that
     * session's files, so a slow exit or a folder of thousands of segments never holds up the other requests.
     */
    private suspend inline fun <T> locked(action: () -> T): T = try { mutex.withLock(action = action) } finally { dispose() }

    private suspend fun dispose() {
        if (retired.isEmpty()) return
        withContext(NonCancellable) { while (true) stop(retired.poll() ?: break) }
    }

    /** Takes [state] out of the sessions and signals its FFmpeg; [dispose] finishes it off the lock. Its id is never reused, so its folder is its own. */
    private fun retire(state: VodState) {
        sessions.remove(state.id)
        state.process?.destroy()
        retired += state
    }

    /** Clears what an earlier run left behind and checks the hardware encoder, if one is set, can encode. */
    suspend fun start() {
        withContext(Dispatchers.IO) { clear(directory) }
        encoder = encoder.verified(ffmpeg)
    }

    suspend fun open(request: OpenVodPlayback): VodSession {
        if (request.startMs < 0) throw ApiFailure(400, "INVALID_REQUEST", "startMs is not negative")
        request.recordingId?.let { return openRecording(it, request) }
        val (item, file) = itemFile(request.itemId)
        val burn = request.subtitleIndex?.let { index ->
            subtitleSource(item, file, index).also {
                if (!it.picture) throw ApiFailure(400, "INVALID_REQUEST", "Subtitle stream $index is text; the player draws it")
            }
        }
        return start(request, item.record.id, null, file, growing = false, item.record.media, item.item.durationMs, burn)
    }

    /**
     * Text subtitle track [index] of the library item [itemId] as WebVTT, in the item's time: a stream of its file,
     * or from [SIDECAR_BASE] a subtitle file beside it. FFmpeg converts it the first time, which reads the whole
     * file, and the copy under `subtitles/` serves after that until the file changes. A picture subtitle (PGS, DVD,
     * DVB) has no text, so it is refused; a player asks for it to be drawn into the picture instead.
     */
    suspend fun subtitles(itemId: String, index: Int): Path {
        val (item, video) = itemFile(itemId)
        val source = subtitleSource(item, video, index)
        if (source.picture) throw ApiFailure(415, "SUBTITLE_NOT_TEXT", "Subtitle stream $index is pictures, not text")
        val file = source.file ?: video
        val map = if (source.file == null) "0:$index" else "0:s:0"
        val dir = directory.resolve(SUBTITLES)
        val stamp = withContext(Dispatchers.IO) { "${Files.getLastModifiedTime(file).toMillis()}-${Files.size(file)}" }
        val name = hash("$itemId/$index/$stamp").take(32)
        val out = dir.resolve("$name.vtt")
        return extractingOnly(name) {
            withContext(Dispatchers.IO) {
                if (!Files.isRegularFile(out)) {
                    Files.createDirectories(dir)
                    val partial = dir.resolve("$name.part")
                    val command = listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y", "-i", file.toString(), "-map", map, "-c:s", "webvtt", "-f", "webvtt", partial.toString())
                    // A client that gives up mid-conversion ends FFmpeg; what it wrote goes with it.
                    try {
                        if (!extractions.withPermit { runs(command, SUBTITLE_TIMEOUT_MINUTES) }) throw ApiFailure(502, "SUBTITLE_FAILED", "The subtitles could not be read from the file")
                        Files.move(partial, out, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(partial)
                    }
                    prune(dir)
                }
                out
            }
        }
    }

    /** Runs [block] holding the lock on subtitle file [name], which goes once nobody holds or waits for it. */
    private suspend fun <T> extractingOnly(name: String, block: suspend () -> T): T {
        val lock = synchronized(extracting) { extracting.getOrPut(name) { ExtractionLock() }.also { it.users++ } }
        try {
            return lock.mutex.withLock { block() }
        } finally {
            synchronized(extracting) { if (--lock.users == 0) extracting.remove(name) }
        }
    }

    /** The library item [itemId] and its file, which must be a movie, episode or video inside its library's folder. */
    private suspend fun itemFile(itemId: String): Pair<View, Path> {
        val snapshot = catalog.snapshot()
        val view = snapshot.views[itemId] ?: throw ApiFailure(404, "ITEM_NOT_FOUND", "No such item")
        if (view.record.path == null || view.record.kind == ItemKind.SHOW || view.record.kind == ItemKind.SEASON) {
            throw ApiFailure(400, "INVALID_REQUEST", "Only a movie, episode or video plays")
        }
        val file = snapshot.fileOf(view)
        if (file == null || withContext(Dispatchers.IO) { !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }) {
            throw ApiFailure(404, "ITEM_MISSING", "The file is not there; is its disk mounted?")
        }
        return view to file
    }

    /** Subtitle track [index] of [item], whose file is [video]: one of its streams, or a subtitle file beside it. */
    private suspend fun subtitleSource(item: View, video: Path, index: Int): SubtitleSource {
        if (index >= SIDECAR_BASE) {
            val sidecar = withContext(Dispatchers.IO) { sidecarSubtitles(video) }.firstOrNull { it.stream.index == index }
            if (sidecar != null) return SubtitleSource(sidecar.stream, sidecar.file)
        } else {
            item.record.media?.subtitles?.firstOrNull { it.index == index }?.let { return SubtitleSource(it, null) }
        }
        throw ApiFailure(404, "RESOURCE_NOT_FOUND", "No subtitle stream $index")
    }

    /** Plays a recording; one still recording is never played directly, as its stream has to follow the file. */
    private suspend fun openRecording(id: String, request: OpenVodPlayback): VodSession {
        if (request.subtitleIndex != null) throw ApiFailure(400, "INVALID_REQUEST", "A recording has no subtitle tracks to draw")
        val playable = recordings?.invoke(id) ?: throw ApiFailure(404, "RECORDING_NOT_FOUND", "No such recording, or nothing recorded yet")
        val size = withContext(Dispatchers.IO) { if (Files.isRegularFile(playable.file, LinkOption.NOFOLLOW_LINKS)) Files.size(playable.file) else -1L }
        if (size < 0) throw ApiFailure(404, "RECORDING_MISSING", "The recording's file is not there; is its disk mounted?")
        if (size == 0L) throw ApiFailure(409, "RECORDING_NOT_READY", "Nothing has been recorded yet; try again in a few seconds", true)
        val media = probe(playable.file)
        return start(request, "", id, playable.file, playable.growing, media, playable.durationMs ?: media?.durationMs)
    }

    private suspend fun start(
        request: OpenVodPlayback, itemId: String, recordingId: String?, file: Path, growing: Boolean, media: MediaInfo?, durationMs: Long?,
        burn: SubtitleSource? = null,
    ): VodSession {
        val audio = request.audioIndex?.let { index -> media?.audio?.firstOrNull { it.index == index } ?: throw ApiFailure(400, "INVALID_REQUEST", "No audio stream $index") }
        // Drawing subtitles into the picture means encoding it.
        val mode = if (burn != null) Wire.VodMode.TRANSCODE
        else vodMode(media, if (growing) request.profile.copy(containers = emptyList()) else request.profile, audio)
        // Only a conversion cuts segments where it likes; a copy cuts at the file's own key frames, which nothing lists.
        val whole = request.wholePlaylist && mode == Wire.VodMode.TRANSCODE && !growing && durationMs != null && durationMs > 0
        return locked {
            reap()
            if (mode != Wire.VodMode.DIRECT && sessions.values.count { it.mode != Wire.VodMode.DIRECT } >= maxConverting) {
                throw ApiFailure(503, Wire.ErrorCode.CAPACITY_EXHAUSTED, "Too many files are being converted; try again when one stops", true)
            }
            val state = VodState(
                UUID.randomUUID().toString(), itemId, recordingId, file, growing, mode, durationMs, audio?.index, burn, media, secret(), epoch = 1,
                startMs = if (mode == Wire.VodMode.DIRECT || whole) 0 else request.startMs, expires = clock.instant().plus(lease), startedAt = clock.instant(),
                whole = whole, runStart = if (whole) (request.startMs / SEGMENT_MS).toInt() else 0,
            )
            // A conversion that fails to start leaves its folder, and maybe its FFmpeg, to dispose.
            if (mode != Wire.VodMode.DIRECT) try { launch(state) } catch (e: Throwable) { retired += state; throw e }
            sessions[state.id] = state
            state.wire()
        }
    }

    suspend fun get(id: String): VodSession = locked { live(id).wire() }

    /** The sessions playing now, oldest first. */
    suspend fun snapshot(): List<VodActivity> = locked {
        reap()
        sessions.values.sortedBy { it.startedAt }.map { VodActivity(it.id, it.itemId, it.recordingId, it.mode, it.startedAt, it.expires) }
    }

    /** Renews [id]'s lease; with a start, restarts a converted stream there under a new epoch. */
    suspend fun update(id: String, update: UpdateVodPlayback): VodSession {
        val start = update.startMs
        if (start != null && start < 0) throw ApiFailure(400, "INVALID_REQUEST", "startMs is not negative")
        // A recording may have finished and moved since the stream started, so a restart finds its file again.
        val recordingId = locked { live(id).recordingId }
        val playable = if (start != null && recordingId != null) recordings?.invoke(recordingId) else null
        val state = locked { live(id).also { it.expires = clock.instant().plus(lease) } }
        // A whole playlist seeks anywhere already; a restart there only renews it.
        if (start != null && state.mode != Wire.VodMode.DIRECT && !state.whole) {
            // Stopped and started off the sessions' lock, so a slow FFmpeg exit holds up only this session.
            state.restarting.withLock {
                stop(state)
                locked {
                    live(id)
                    playable?.let { state.file = it.file; state.growing = it.growing }
                    state.epoch++
                    state.startMs = start
                }
                launchOwned(state)
            }
        }
        return locked { live(id).wire() }
    }

    /** [launch], then retires the new FFmpeg at once when the session was released or expired while it started. */
    private suspend fun launchOwned(state: VodState) {
        launch(state)
        locked { if (sessions[state.id] !== state) retire(state) }
    }

    suspend fun release(id: String): Boolean = locked { sessions[id]?.also(::retire) != null }

    suspend fun expire() = locked { reap() }

    /** Stops every session, as the server shuts down. */
    suspend fun close() = locked { sessions.values.toList().forEach(::retire) }

    /** The file behind a media URL, waiting a little for FFmpeg to write it; null when the URL is stale or unknown. */
    suspend fun media(id: String, epoch: Int, secret: String, name: String): VodMedia? {
        val state = locked {
            val state = sessions[id]?.takeIf { it.epoch == epoch && sameSecret(it.secret, secret) } ?: return null
            state.expires = clock.instant().plus(lease)
            state
        }
        if (state.mode == Wire.VodMode.DIRECT) {
            return if (name == "file.${extension(state.media)}") VodMedia(state.file, directType(state.media)) else null
        }
        val dir = directory.resolve(state.id)
        if (state.whole) return wholeMedia(state, dir, name)
        return when {
            name == "index.m3u8" -> waitFor(state, epoch) { Files.isRegularFile(dir.resolve(name)) && Files.isRegularFile(dir.resolve(firstSegment(state))) }
                ?.let { VodMedia(dir.resolve(name), "application/vnd.apple.mpegurl") }
            SEGMENT.matches(name) -> waitFor(state, epoch) { Files.isRegularFile(dir.resolve(name)) }?.let { VodMedia(dir.resolve(name), "video/mp2t") }
            FMP4_SEGMENT.matches(name) || name == FMP4_INIT -> waitFor(state, epoch) { Files.isRegularFile(dir.resolve(name)) }?.let { VodMedia(dir.resolve(name), "video/mp4") }
            else -> null
        }
    }

    /**
     * A whole-playlist session's media: the playlist, written once, or a segment, converted from there when FFmpeg is
     * not already on its way to it.
     */
    private suspend fun wholeMedia(state: VodState, dir: Path, name: String): VodMedia? {
        if (name == "index.m3u8") return VodMedia(dir.resolve(name), "application/vnd.apple.mpegurl")
        val segment = SEGMENT.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (segment >= segmentCount(state.durationMs!!)) return null
        val file = dir.resolve(name)
        if (withContext(Dispatchers.IO) { Files.isRegularFile(file) }) return VodMedia(file, "video/mp2t")
        val epoch = state.restarting.withLock {
            val jump = locked {
                if (sessions[state.id] !== state) return null
                val running = state.process?.isAlive == true
                val reached = withContext(Dispatchers.IO) { written(dir, state.runStart) }
                !running || segment < state.runStart || segment > reached + LOOKAHEAD_SEGMENTS
            }
            if (jump) {
                stop(state, clearFiles = false)
                locked { state.runStart = segment }
                launchOwned(state)
            }
            state.epoch
        }
        return waitFor(state, epoch) { Files.isRegularFile(file) }?.let { VodMedia(file, "video/mp2t") }
    }

    private suspend fun waitFor(state: VodState, epoch: Int, ready: () -> Boolean): Unit? {
        repeat(200) {
            if (withContext(Dispatchers.IO) { ready() }) return Unit
            val running = locked { state.epoch == epoch && state.process?.isAlive == true && sessions[state.id] === state }
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
        sessions.values.filter { it.expires <= now }.forEach(::retire)
    }

    private suspend fun launch(state: VodState) = withContext(Dispatchers.IO) {
        val dir = directory.resolve(state.id)
        // A whole playlist keeps the segments already converted, so a seek back to them plays at once.
        if (!state.whole || !Files.isDirectory(dir)) {
            clear(dir)
            Files.createDirectories(dir)
            if (state.whole) Files.writeString(dir.resolve("index.m3u8"), wholePlaylist(state.durationMs!!))
        }
        val command = if (state.whole) {
            vodCommand(ffmpeg, state.file, dir, state.mode, state.runStart * SEGMENT_MS, state.media, state.audioIndex, encoder = encoder, burn = state.burn, firstSegment = state.runStart)
        } else {
            vodCommand(ffmpeg, state.file, dir, state.mode, state.startMs, state.media, state.audioIndex, follow = state.growing, encoder = encoder, burn = state.burn)
        }
        state.process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("ffmpeg.log").toFile()).start()
        } catch (e: IOException) {
            log.warn("vod ffmpeg did not start class={}", e.javaClass.simpleName)
            throw ApiFailure(503, "TRANSCODER_UNAVAILABLE", "FFmpeg could not start", true)
        }
        log.info("vod session started session={} mode={} startMs={}", state.id, state.mode, if (state.whole) state.runStart * SEGMENT_MS else state.startMs)
    }

    private suspend fun stop(state: VodState, clearFiles: Boolean = true) {
        val process = state.process
        state.process = null
        withContext(Dispatchers.IO) {
            process?.terminate()
            if (clearFiles) clear(directory.resolve(state.id))
        }
    }

    private fun VodState.wire(): VodSession {
        val base = "/media/vod/$id/$epoch/$secret"
        val descriptor = if (mode == Wire.VodMode.DIRECT) PlaybackDescriptor("file", directType(media), "$base/file.${extension(media)}", epoch)
        else PlaybackDescriptor("hls", "application/vnd.apple.mpegurl", "$base/index.m3u8", epoch)
        return VodSession(id, itemId, mode, descriptor, startMs, durationMs, expires.toString(), recordingId)
    }

    private fun secret(): String = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))

    private companion object {
        val SEGMENT = Regex("seg([0-9]{1,6})\\.ts")

        /** How far past the last segment written a request may be and still wait for the running FFmpeg. */
        const val LOOKAHEAD_SEGMENTS = 3

        /** The last segment written in a row from [start], or `start - 1` before the first. */
        fun written(dir: Path, start: Int): Int {
            var last = start - 1
            while (Files.isRegularFile(dir.resolve("seg${last + 1}.ts"))) last++
            return last
        }
        val FMP4_SEGMENT = Regex("seg[0-9]{1,6}\\.m4s")

        fun firstSegment(state: VodState) = if (fragmentedMp4(state.mode, state.media)) "seg0.m4s" else "seg0.ts"

        fun sameSecret(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        /** The extracted subtitle files kept; the oldest go first. */
        const val SUBTITLES_KEPT = 200

        fun prune(dir: Path) {
            val files = Files.list(dir).use { paths -> paths.filter { it.fileName.toString().endsWith(".vtt") }.toList() }
            if (files.size <= SUBTITLES_KEPT) return
            files.sortedBy { Files.getLastModifiedTime(it) }.take(files.size - SUBTITLES_KEPT).forEach { runCatching { Files.deleteIfExists(it) } }
        }

        /** Whether [command] exits 0 within [minutes]; a client giving up on the request ends FFmpeg too. */
        suspend fun runs(command: List<String>, minutes: Long): Boolean = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).runFor(minutes.minutes) == 0
        } catch (_: IOException) {
            false
        }

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
 * stream the viewer picked; a pick other than the file's default is never played directly, since the players start
 * on the default and the stream has to carry the pick.
 */
internal fun vodMode(media: MediaInfo?, profile: ClientProfile, audio: AudioStream? = null): String {
    val video = media?.video ?: return Wire.VodMode.TRANSCODE
    val default = media.audio.firstOrNull { it.default } ?: media.audio.firstOrNull()
    val track = audio ?: default
    val videoFits = video.codec in profile.videoCodecs &&
        (profile.maxHeight?.let { (video.height ?: 0) <= it } ?: true) && (video.transfer == null || profile.hdr) &&
        (!deepColor(video) || video.codec in (profile.deepColor ?: profile.videoCodecs - "h264"))
    val audioFits = track == null || track.codec in profile.audioCodecs
    val container = containerOf(media.container)
    // HEVC tagged `hev1` in MP4 plays on Android but not in AVPlayer; the remux retags it `hvc1`, which plays on both.
    val containerFits = (container in profile.containers && !(container == "mp4" && video.codec == "hevc" && video.tag == "hev1")) ||
        (container == "mkv" && "webm" in profile.containers && video.codec in setOf("vp8", "vp9", "av1"))
    return when {
        videoFits && audioFits && containerFits && track?.index == default?.index -> Wire.VodMode.DIRECT
        videoFits && video.codec in TS_VIDEO && (track == null || (audioFits && track.codec in TS_AUDIO)) -> Wire.VodMode.REMUX
        videoFits && video.codec in TS_VIDEO -> Wire.VodMode.AUDIO
        else -> Wire.VodMode.TRANSCODE
    }
}

/** Pixel formats with more than 8 bits a sample, such as `yuv420p10le` and `p010le`. */
private val DEEP_PIXEL_FORMAT = Regex("""(p9|p1[0-6]|p01[0-6])(le|be)?$""")

/**
 * True when [video] has more than 8 bits a sample, as HEVC Main 10 and H.264 High 10 do. Most hardware decoders take
 * 10-bit HEVC, VP9 and AV1, but no phone, TV or browser takes 10-bit H.264.
 */
internal fun deepColor(video: VideoStream): Boolean =
    (video.bitDepth ?: 0) > 8 || video.pixelFormat?.let { DEEP_PIXEL_FORMAT.containsMatchIn(it) } == true

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
    val audio = audioIndex ?: media?.audio?.let { tracks -> (tracks.firstOrNull { it.default } ?: tracks.firstOrNull())?.index }
    val copyVideo = mode == Wire.VodMode.REMUX || mode == Wire.VodMode.AUDIO
    val copyAudio = mode == Wire.VodMode.REMUX
    val fmp4 = fragmentedMp4(mode, media)
    return buildList {
        addAll(listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error"))
        if (!copyVideo) addAll(encoder.inputArgs())
        val seek = if (startMs > 0) listOf("-ss", "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0)) else emptyList()
        addAll(seek)
        if (follow) addAll(listOf("-follow", "1", "-rw_timeout", "$FOLLOW_TIMEOUT_MICROS"))
        addAll(listOf("-i", file.toString()))
        // A subtitle file is a second input, seeked to the same point.
        burn?.file?.let { addAll(seek + listOf("-i", it.toString())) }
        val subtitle = burn?.let { if (it.file == null) "0:${it.stream.index}" else "1:s:0" }
        if (subtitle == null) addAll(listOf("-map", "0:v:0?")) else addAll(encoder.overlayGraph(subtitle, media?.video))
        if (audio != null) addAll(listOf("-map", "0:$audio")) else add("-an")
        addAll(listOf("-sn", "-dn"))
        if (copyVideo) {
            addAll(listOf("-c:v", "copy"))
            if (fmp4) addAll(listOf("-tag:v", "hvc1"))
        } else if (subtitle != null) {
            addAll(encoder.codecArgs(keyFrameSeconds = SEGMENT_SECONDS))
        } else {
            addAll(encoder.outputArgs(hdr = media?.video?.transfer != null, keyFrameSeconds = SEGMENT_SECONDS))
        }
        if (audio != null) {
            if (copyAudio) addAll(listOf("-c:a", "copy")) else addAll(listOf("-c:a", "aac", "-ac", "2", "-b:a", "192k"))
        }
        if (firstSegment != null) {
            addAll(listOf("-output_ts_offset", "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0), "-start_number", "$firstSegment"))
        } else {
            addAll(listOf("-avoid_negative_ts", "make_zero"))
        }
        addAll(listOf(
            "-f", "hls", "-hls_time", "$SEGMENT_SECONDS", "-hls_list_size", "0",
            "-hls_playlist_type", "event", "-hls_flags", "temp_file+independent_segments",
        ))
        if (fmp4) {
            addAll(listOf("-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", FMP4_INIT, "-hls_segment_filename", dir.resolve("seg%d.m4s").toString()))
        } else {
            addAll(listOf("-hls_segment_type", "mpegts", "-hls_segment_filename", dir.resolve("seg%d.ts").toString()))
        }
        add(dir.resolve(if (firstSegment != null) "ffmpeg.m3u8" else "index.m3u8").toString())
    }
}
