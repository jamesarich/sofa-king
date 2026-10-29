package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.backend.api.SessionStart
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.playback.HistoryKind
import dev.jamesrich.sofaking.backend.playback.HistoryReason
import dev.jamesrich.sofaking.backend.playback.PlaybackClient
import dev.jamesrich.sofaking.backend.playback.SessionHistory
import dev.jamesrich.sofaking.backend.randomHex
import dev.jamesrich.sofaking.contract.OpenVodPlayback
import dev.jamesrich.sofaking.contract.PlaybackDescriptor
import dev.jamesrich.sofaking.contract.UpdateVodPlayback
import dev.jamesrich.sofaking.contract.VodSession
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The HLS segment length FFmpeg cuts to, in seconds. */
internal const val SEGMENT_SECONDS = 4
internal const val SEGMENT_MS = SEGMENT_SECONDS * MS_PER_SECOND.toLong()

/**
 * How many segments a whole playlist of an item [durationMs] long lists. Less than a second left over joins the last
 * segment, as the stream's last frames can end that much before its audio does.
 */
internal fun segmentCount(durationMs: Long): Int =
    maxOf(1, ((durationMs + SEGMENT_MS - MS_PER_SECOND) / SEGMENT_MS).toInt())

/** A VOD playlist of every segment of an item [durationMs] long, the last one as long as what is left. */
internal fun wholePlaylist(durationMs: Long): String = buildString {
    append(
        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:${SEGMENT_SECONDS + 1}\n#EXT-X-MEDIA-SEQUENCE:" +
            "0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n",
    )
    val count = segmentCount(durationMs)
    for (i in 0 until count) {
        val length = if (i == count - 1) durationMs - i * SEGMENT_MS else SEGMENT_MS
        append("#EXTINF:%.3f,\nseg$i.ts\n".format(java.util.Locale.ROOT, length.toDouble() / MS_PER_SECOND))
    }
    append("#EXT-X-ENDLIST\n")
}

/** The init segment of a fragmented MP4 stream. */
internal const val FMP4_INIT = "init.mp4"

/** Subtitle codecs FFmpeg converts to WebVTT; the others are pictures. */
private val TEXT_SUBTITLES = setOf("subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text")

/**
 * A subtitle track to read or draw: stream [index] of the item's own file when [file] is null, else the subtitle
 * file [file] beside it.
 */
internal class SubtitleSource(val stream: SubtitleStream, val file: Path?) {
    val picture: Boolean get() = stream.codec !in TEXT_SUBTITLES
}

/** How long an idle VOD session lasts. */
private val LEASE: Duration = Duration.ofMinutes(5)

private const val SECRET_BYTES = 16

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
    /** Who opened it, for the history. */
    val client: PlaybackClient? = null,
) {
    /** Held while this session's FFmpeg is stopped and started again, off the sessions' lock. */
    val restarting = Mutex()

    /** The session as the history records its start. */
    fun start(): SessionStart = SessionStart(
        id,
        HistoryKind.VOD,
        client?.clientId,
        client?.address,
        itemId = itemId.ifEmpty { null },
        recordingId = recordingId,
        mode = mode,
        startedAt = startedAt.toString(),
    )
}

/** A library file or recording being played, as the portal's Watching now lists it. */
class VodActivity(
    val id: String,
    val itemId: String,
    val recordingId: String?,
    val mode: String,
    val startedAt: Instant,
    val expiresAt: Instant,
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
    catalog: LibraryCatalog,
    private val clock: Clock,
    private val ffmpeg: String,
    private val directory: Path,
    private val maxConverting: Int = 2,
    private val lease: Duration = LEASE,
    private val recordings: (suspend (String) -> PlayableFile?)? = null,
    private val probe: suspend (Path) -> MediaInfo? = { null },
    encoder: VideoEncoder = VideoEncoder(),
    /** Conversions running elsewhere that count toward [maxConverting], such as virtual channels. */
    private val others: () -> Int = { 0 },
    /** Held across the capacity check and the session's insert, and shared with the converters counted in [others]. */
    private val gate: Mutex = Mutex(),
) {
    private val runner = VodRunner(ffmpeg, directory, encoder)

    /** The encoder conversions use, once [start] has checked it. */
    val encoder: VideoEncoder get() = runner.encoder

    /** The startup check of [encoder] and the transcode test, for the About and Home pages. */
    val encoders = EncoderChecks(ffmpeg, clock)

    private val table = VodTable(clock, runner)

    /** Where each session's start and end is recorded; none until the runtime sets it. */
    var history: SessionHistory?
        get() = table.history
        set(value) {
            table.history = value
        }

    /** The items that play, and their text subtitles as WebVTT. */
    val subtitles = VodSubtitles(catalog, ffmpeg, directory)

    /** The files behind the sessions' media URLs. */
    val files = VodMediaFiles(table, runner, clock, lease)

    /** Clears what an earlier run left behind and checks the hardware encoder, if one is set, can encode. */
    suspend fun start() {
        withContext(Dispatchers.IO) { VodRunner.clear(directory) }
        runner.encoder = encoders.verify(runner.encoder)
    }

    /** [from] is the player opening the session, for the history. */
    suspend fun open(request: OpenVodPlayback, from: PlaybackClient? = null): VodSession {
        if (request.startMs < 0) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "startMs is not negative")
        }
        request.recordingId?.let { return openRecording(it, request, from) }
        val (item, file) = subtitles.itemFile(request.itemId)
        val burn = request.subtitleIndex?.let { index ->
            subtitles.sourceOf(item, file, index).also {
                if (!it.picture) {
                    throw ApiFailure(
                        HttpStatusCode.BadRequest,
                        "INVALID_REQUEST",
                        "Subtitle stream $index is text; the player draws it",
                    )
                }
            }
        }
        return begin(
            request,
            item.record.id,
            null,
            file,
            growing = false,
            item.record.media,
            item.item.durationMs,
            burn,
            from,
        )
    }

    /** Plays a recording; one still recording is never played directly, as its stream has to follow the file. */
    private suspend fun openRecording(id: String, request: OpenVodPlayback, from: PlaybackClient?): VodSession {
        if (request.subtitleIndex != null) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "A recording has no subtitle tracks to draw")
        }
        val playable =
            recordings?.invoke(id)
                ?: throw ApiFailure(
                    HttpStatusCode.NotFound,
                    "RECORDING_NOT_FOUND",
                    "No such recording, or nothing recorded yet",
                )
        val size =
            withContext(Dispatchers.IO) {
                if (Files.isRegularFile(playable.file, LinkOption.NOFOLLOW_LINKS)) Files.size(playable.file) else -1L
            }
        if (size < 0) {
            throw ApiFailure(
                HttpStatusCode.NotFound,
                "RECORDING_MISSING",
                "The recording's file is not there; is its disk mounted?",
            )
        }
        if (size == 0L) {
            throw ApiFailure(
                HttpStatusCode.Conflict,
                "RECORDING_NOT_READY",
                "Nothing has been recorded yet; try again in a few seconds",
                true,
            )
        }
        val media = probe(playable.file)
        val duration = playable.durationMs ?: media?.durationMs
        return begin(request, "", id, playable.file, playable.growing, media, duration, from = from)
    }

    private suspend fun begin(
        request: OpenVodPlayback,
        itemId: String,
        recordingId: String?,
        file: Path,
        growing: Boolean,
        media: MediaInfo?,
        durationMs: Long?,
        burn: SubtitleSource? = null,
        from: PlaybackClient? = null,
    ): VodSession {
        val audio = request.audioIndex?.let { index ->
            media?.audio?.firstOrNull { it.index == index }
                ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "No audio stream $index")
        }
        val mode = modeOf(request, media, audio, growing, burn)
        // Only a conversion cuts segments where it likes; a copy cuts at the file's own
        // key frames, which nothing lists.
        val whole =
            request.wholePlaylist && mode == Wire.VodMode.TRANSCODE && !growing && durationMs != null && durationMs > 0
        return gate.withLock {
            table.locked {
                table.reap()
                val converting = table.sessions.values.count { heavy(it.mode, it.media) } + others()
                if (heavy(mode, media) && converting >= maxConverting) {
                    throw ApiFailure(
                        HttpStatusCode.ServiceUnavailable,
                        Wire.ErrorCode.CAPACITY_EXHAUSTED,
                        "Too many files are being converted; try again when one stops",
                        true,
                    )
                }
                val state = VodState(
                    UUID.randomUUID().toString(), itemId, recordingId, file, growing, mode, durationMs,
                    audio?.index, burn, media, secret(), epoch = 1,
                    startMs = if (mode == Wire.VodMode.DIRECT || whole) 0 else request.startMs,
                    expires = clock.instant().plus(lease), startedAt = clock.instant(),
                    whole = whole, client = from,
                    runStart = if (whole) (request.startMs / SEGMENT_MS).toInt() else 0,
                )
                // A conversion that fails to start leaves its folder, and maybe its FFmpeg, to dispose.
                if (mode != Wire.VodMode.DIRECT) {
                    runCatching { runner.launch(state) }.onFailure { table.retired += state }.getOrThrow()
                }
                table.add(state)
                state.wire()
            }
        }
    }

    suspend fun get(id: String): VodSession = table.locked { table.live(id).wire() }

    /** The sessions playing now, oldest first. */
    suspend fun snapshot(): List<VodActivity> = table.locked {
        table.reap()
        table.sessions.values.sortedBy { it.startedAt }
            .map { VodActivity(it.id, it.itemId, it.recordingId, it.mode, it.startedAt, it.expires) }
    }

    /** Renews [id]'s lease; with a start, restarts a converted stream there under a new epoch. */
    suspend fun update(id: String, update: UpdateVodPlayback): VodSession {
        val start = update.startMs
        if (start != null && start < 0) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "startMs is not negative")
        }
        // A recording may have finished and moved since the stream started, so a restart finds its file again.
        val recordingId = table.locked { table.live(id).recordingId }
        val playable = if (start != null && recordingId != null) recordings?.invoke(recordingId) else null
        val state = table.locked { table.live(id).also { it.expires = clock.instant().plus(lease) } }
        // A whole playlist seeks anywhere already; a restart there only renews it.
        if (start != null && state.mode != Wire.VodMode.DIRECT && !state.whole) {
            // Stopped and started off the sessions' lock, so a slow FFmpeg exit holds up only this session.
            state.restarting.withLock {
                runner.stop(state)
                table.locked {
                    table.live(id)
                    playable?.let {
                        state.file = it.file
                        state.growing = it.growing
                    }
                    state.epoch++
                    state.startMs = start
                }
                table.launchOwned(state)
            }
        }
        return table.locked { table.live(id).wire() }
    }

    suspend fun release(id: String): Boolean = table.locked {
        table.sessions[id]?.also { table.retire(it, HistoryReason.RELEASED) } !=
            null
    }

    suspend fun expire() = table.locked { table.reap() }

    /** Stops every session, as the server shuts down. */
    suspend fun close() = table.locked {
        table.sessions.values.toList().forEach { table.retire(it, HistoryReason.STOPPED) }
    }

    private fun secret(): String = randomHex(SECRET_BYTES)
}

/**
 * Whether a session in [mode] takes one of the conversions `SOFA_MAX_TRANSCODES` allows: converting sound alone costs
 * little, so music never waits for one or holds one.
 */
private fun heavy(mode: String, media: MediaInfo?) = mode != Wire.VodMode.DIRECT && !media.audioOnly

/** Drawing subtitles into the picture means encoding it, and a file still recording is never played directly. */
private fun modeOf(
    request: OpenVodPlayback,
    media: MediaInfo?,
    audio: AudioStream?,
    growing: Boolean,
    burn: SubtitleSource?,
): String = when {
    burn != null -> Wire.VodMode.TRANSCODE
    growing -> vodMode(media, request.profile.copy(containers = emptyList()), audio)
    else -> vodMode(media, request.profile, audio)
}

private fun VodState.wire(): VodSession {
    val base = "/media/vod/$id/$epoch/$secret"
    val descriptor = if (mode == Wire.VodMode.DIRECT) {
        PlaybackDescriptor("file", directType(media), "$base/file.${extension(media)}", epoch)
    } else {
        PlaybackDescriptor("hls", "application/vnd.apple.mpegurl", "$base/index.m3u8", epoch)
    }
    return VodSession(id, itemId, mode, descriptor, startMs, durationMs, expires.toString(), recordingId)
}

/** The container family a client names for FFprobe's format names. */
internal fun containerOf(format: String?): String = when {
    format == null -> ""
    format.startsWith("matroska") -> "mkv"
    format.startsWith("mov,mp4") -> "mp4"
    format == "mpegts" -> "ts"
    else -> format.substringBefore(',')
}

internal fun extension(media: MediaInfo?): String = when {
    media.audioOnly && containerOf(media?.container) == "mp4" -> "m4a"
    else -> containerOf(media?.container).ifEmpty { "bin" }
}

internal fun directType(media: MediaInfo?): String = if (media.audioOnly) {
    audioType(media)
} else {
    when (containerOf(media?.container)) {
        "mkv" -> "video/x-matroska"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }
}

private fun audioType(media: MediaInfo?): String = when (containerOf(media?.container)) {
    "flac" -> "audio/flac"
    "mp3" -> "audio/mpeg"
    "mp4" -> "audio/mp4"
    "ogg" -> "audio/ogg"
    else -> "application/octet-stream"
}
