package dev.jamesrich.sofaking.backend.virtual

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.io.terminate
import dev.jamesrich.sofaking.backend.library.VideoEncoder
import dev.jamesrich.sofaking.backend.library.VodRunner
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Plays virtual channels as live HLS. The first read of a channel's playlist starts its stream: one FFmpeg after
 * another, each writing one airing (see [pieceCommand]) into [directory], from [LEAD] before now. The server lists the
 * segments as their scheduled times come, a few at a time, with a discontinuity where one airing gives way to the next,
 * so every viewer sees the same picture at the same time. A stream nobody has read for [IDLE] stops; [mayStart] says
 * whether another FFmpeg may convert now, as virtual channels share `SOFA_MAX_TRANSCODES` with library playback.
 */
class VirtualStreams(
    private val ffmpeg: String,
    private val directory: Path,
    private val channels: VirtualChannelService,
    private val clock: Clock,
    private val scope: CoroutineScope,
    @Volatile var encoder: VideoEncoder = VideoEncoder(),
    private val mayStart: suspend () -> Boolean = { true },
) {
    private val log = LoggerFactory.getLogger(VirtualStreams::class.java)
    private val streams = ConcurrentHashMap<String, Stream>()

    /** Streams playing now, each one FFmpeg. */
    val running: Int get() = streams.size

    /** Answers [call] with file [name] of channel [id]'s stream: its live playlist, or one of its segments. */
    suspend fun serve(call: ApplicationCall, id: String, name: String) {
        if (name == LIVE_PLAYLIST) {
            call.respondText(playlist(id), HLS)
            return
        }
        val file = segment(id, name) ?: return call.respond(HttpStatusCode.NotFound)
        call.respondBytes(withContext(Dispatchers.IO) { Files.readAllBytes(file) }, TS)
    }

    /**
     * The live playlist of channel [id], starting its stream when it is not playing; a stream just started is waited
     * for until it lists [START_SEGMENTS] segments, or for [START_WAIT].
     */
    suspend fun playlist(id: String): String {
        channels.channel(id)
        val stream = streams[id] ?: run {
            if (!mayStart()) {
                throw ApiFailure(
                    HttpStatusCode.ServiceUnavailable,
                    Wire.ErrorCode.CAPACITY_EXHAUSTED,
                    "Too many files are being converted; try again when one stops",
                    true,
                )
            }
            start(id)
        }
        stream.readAt = clock.instant()
        val listed = withTimeoutOrNull(START_WAIT.toMillis()) {
            var listed = stream.listed()
            while (listed.size < START_SEGMENTS && stream.job.isActive) {
                delay(POLL_MS)
                listed = stream.listed()
            }
            listed
        } ?: stream.listed()
        stream.sweep()
        return playlistText(listed)
    }

    /** Segment [name] of channel [id] while it is on disk, as the playlist named it. */
    fun segment(id: String, name: String): Path? {
        val stream = streams[id] ?: return null
        stream.readAt = clock.instant()
        val match = SEGMENT_NAME.matchEntire(name) ?: return null
        val (piece, index) = match.destructured
        return stream.dir.resolve(piece).resolve("seg$index.ts").takeIf { Files.isRegularFile(it) }
    }

    /** Stops the streams nobody has read for [IDLE]. */
    suspend fun expire() {
        val now = clock.instant()
        for ((id, stream) in streams) {
            if (Duration.between(stream.readAt, now) > IDLE) stop(id, stream)
        }
    }

    /** Stops every stream; the server is shutting down. */
    suspend fun close() {
        for ((id, stream) in streams) stop(id, stream)
    }

    private suspend fun stop(id: String, stream: Stream) {
        if (!streams.remove(id, stream)) return
        stream.job.cancel()
        stream.job.join()
        withContext(Dispatchers.IO) { VodRunner.clear(stream.dir) }
        log.info("virtual stream stopped channel={}", id)
    }

    private fun start(id: String): Stream = synchronized(streams) {
        streams[id]?.let { return it }
        val now = clock.instant()
        val stream = Stream(directory.resolve(id), now)
        VodRunner.clear(stream.dir)
        stream.job = scope.launch(CoroutineName("virtual:$id")) { stream.play(id, now.minus(LEAD)) }
        streams[id] = stream
        log.info("virtual stream started channel={}", id)
        stream
    }

    /** One finished or running piece: one airing, or the part of it from where the stream began. */
    private class Piece(val number: Int, val from: Instant, val dir: Path, val firstSequence: Long) {
        @Volatile var done = false
            private set

        @Volatile private var known: List<Double> = emptyList()

        /** The durations of the segments FFmpeg has finished, in seconds, read again until the piece is [done]. */
        @Synchronized fun durations(): List<Double> {
            if (done) return known
            val text = runCatching { Files.readString(dir.resolve(PIECE_PLAYLIST)) }.getOrNull() ?: return known
            known = text.lineSequence().filter { it.startsWith("#EXTINF:") }
                .mapNotNull { it.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() }.toList()
            return known
        }

        /** Reads the durations once more, now that FFmpeg has exited, and keeps them. */
        @Synchronized fun seal(): List<Double> = durations().also { done = true }
    }

    private inner class Stream(val dir: Path, val epoch: Instant) {
        @Volatile var readAt: Instant = clock.instant()
        lateinit var job: Job
        private val pieces = ArrayList<Piece>()

        private var sequence = 0L
        private var number = 0

        /** Plays the schedule from [from], one airing after another, until it is stopped. */
        suspend fun play(id: String, from: Instant) {
            var at = from
            while (coroutineContext.isActive) {
                val wait = Duration.between(clock.instant(), at.minus(LEAD)).toMillis()
                if (wait > 0) delay(wait)
                val airing = runCatching { channels.airingsFrom(id, at, at.plusSeconds(1)).firstOrNull() }
                    .onFailure { if (it is CancellationException) throw it }.getOrNull()
                val until = when {
                    airing == null -> at.plus(BLANK_PIECE)
                    airing.startsAt > at -> airing.startsAt
                    else -> airing.endsAt
                }
                write(input(airing, at, until), at)
                at = until
            }
        }

        /**
         * What FFmpeg reads for [airing] from [at] until [until]: its file, its live channel, or a blank picture when
         * it has neither.
         */
        private suspend fun input(airing: Airing?, at: Instant, until: Instant): PieceInput {
            val durationMs = Duration.between(at, until).toMillis()
            val start = airing?.startsAt ?: at
            val id = airing?.itemId?.takeIf { start <= at }
            val live = liveEntryOf(id)
            val item = if (live == null) id?.let { channels.playable(it) } else null
            return when {
                live != null -> PieceInput(null, null, 0, durationMs, live)
                item == null -> PieceInput(null, null, 0, durationMs)
                else -> PieceInput(item.file, item.media, Duration.between(start, at).toMillis(), durationMs)
            }
        }

        /**
         * Runs the FFmpeg of one piece from [at] to its end. One that fails or runs short keeps the segments it wrote,
         * and a blank picture covers the rest of its time. A live channel is opened again for the rest instead, which
         * picks its next source if this one went bad; one that wrote nothing waits [LIVE_RETRY_MS] on a blank picture
         * first.
         */
        private suspend fun write(input: PieceInput, at: Instant) {
            var from = at
            var left = input.durationMs
            var current = input
            var burst = LEAD_SECONDS + BURST_SECONDS
            while (left >= ScheduleBuilder.MIN_BLANK_MS) {
                val piece = piece(from)
                val ok = run(piece, current, burst)
                val written = finish(piece)
                val blank = current.file == null && current.live == null
                // FFmpeg that cannot even draw a blank picture would only fail again.
                if (!ok && written == 0L && blank) return
                from = from.plusMillis(written)
                left -= written
                burst = BURST_SECONDS
                current = when {
                    input.live == null -> PieceInput(null, null, 0, left)
                    current.live != null && written == 0L -> PieceInput(null, null, 0, minOf(left, LIVE_RETRY_MS))
                    else -> PieceInput(null, null, 0, left, input.live)
                }
            }
        }

        private fun piece(at: Instant): Piece = Piece(number, at, dir.resolve("$number"), sequence).also {
            number++
            synchronized(pieces) { pieces += it }
        }

        /** Marks [piece] done and returns how many milliseconds of segments it wrote. */
        private fun finish(piece: Piece): Long {
            val durations = piece.seal()
            sequence += durations.size
            return (durations.sum() * MS_PER_SECOND).toLong()
        }

        private suspend fun run(piece: Piece, input: PieceInput, burstSeconds: Int): Boolean {
            val entry = input.live ?: return run(piece, input, burstSeconds, null)
            val lineup = channels.live ?: return false
            return runCatchingCancellable {
                lineup.reading(entry) { source -> run(piece, input, burstSeconds, source) }
            }.getOrElse { e ->
                // Messages can carry upstream URLs; only the class is logged.
                log.warn("virtual live slot did not open entry={} class={}", entry, e.javaClass.simpleName)
                false
            }
        }

        private suspend fun run(piece: Piece, input: PieceInput, burstSeconds: Int, source: FfmpegSource?): Boolean {
            val timestamp = Duration.between(epoch, piece.from).toMillis()
            val command = pieceCommand(ffmpeg, input, piece.dir, encoder, timestamp, burstSeconds, source)
            val process = try {
                withContext(Dispatchers.IO) {
                    Files.createDirectories(piece.dir)
                    ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(piece.dir.resolve("ffmpeg.log").toFile()).start()
                }
            } catch (e: IOException) {
                log.warn("virtual ffmpeg did not start class={}", e.javaClass.simpleName)
                return false
            }
            try {
                val code = process.onExit().await().exitValue()
                if (code != 0) log.warn("virtual ffmpeg failed piece={} exit={}", piece.number, code)
                return code == 0
            } finally {
                process.terminate()
            }
        }

        /** The segments to list now: those whose time has come, the last [WINDOW] of them. */
        fun listed(): List<ListedSegment> {
            val now = clock.instant()
            val all = ArrayList<ListedSegment>()
            for (piece in synchronized(pieces) { pieces.toList() }) {
                var startsAt = piece.from
                for ((index, seconds) in piece.durations().withIndex()) {
                    if (startsAt > now) break
                    all += ListedSegment(piece.firstSequence + index, piece.number, index, startsAt, seconds)
                    startsAt = startsAt.plusMillis((seconds * MS_PER_SECOND).toLong())
                }
            }
            return all.takeLast(WINDOW)
        }

        /** Deletes the pieces and segments that ended more than [KEEP] ago. */
        fun sweep() {
            val before = clock.instant().minus(KEEP)
            synchronized(pieces) {
                val old = pieces.filter { it.done && it !== pieces.last() && it.end() < before }
                pieces.removeAll(old.toSet())
                old.forEach { VodRunner.clear(it.dir) }
                for (piece in pieces) {
                    var startsAt = piece.from
                    for ((index, seconds) in piece.durations().withIndex()) {
                        startsAt = startsAt.plusMillis((seconds * MS_PER_SECOND).toLong())
                        if (startsAt < before) Files.deleteIfExists(piece.dir.resolve("seg$index.ts"))
                    }
                }
            }
        }

        private fun Piece.end(): Instant = from.plusMillis((durations().sum() * MS_PER_SECOND).toLong())
    }

    /** One segment as the live playlist lists it. */
    internal class ListedSegment(
        val sequence: Long,
        val piece: Int,
        val index: Int,
        val startsAt: Instant,
        val seconds: Double,
    )

    companion object {
        /** The name of each stream's live playlist. */
        const val LIVE_PLAYLIST = "live.m3u8"
        private val HLS = ContentType.parse("application/vnd.apple.mpegurl")
        private val TS = ContentType.parse("video/mp2t")

        /** How far behind the clock a stream starts, so a player has segments to start from at once. */
        val LEAD: Duration = Duration.ofSeconds(12)
        private const val LEAD_SECONDS = 12

        /** How far ahead of its time FFmpeg may read, beyond [LEAD]. */
        private const val BURST_SECONDS = 8

        /** How long a live slot that would not open shows a blank picture before it is tried again. */
        private const val LIVE_RETRY_MS = 30_000L

        /** How long a stream nobody reads keeps playing. */
        val IDLE: Duration = Duration.ofSeconds(60)

        /** How long a segment stays on disk after its time. */
        val KEEP: Duration = Duration.ofMinutes(2)

        private val START_WAIT: Duration = Duration.ofSeconds(20)
        private const val START_SEGMENTS = 3
        private const val POLL_MS = 250L

        /** The segments a playlist lists. */
        internal const val WINDOW = 6

        /** What a stream plays when the schedule has nothing, before it asks again. */
        private val BLANK_PIECE: Duration = Duration.ofSeconds(30)

        private val SEGMENT_NAME = Regex("""(\d{1,9})-(\d{1,9})\.ts""")

        /**
         * The live media playlist of [segments]: their sequence numbers, a discontinuity where a piece begins, and the
         * time each one starts.
         */
        internal fun playlistText(segments: List<ListedSegment>): String = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:${CHANNEL_SEGMENT_SECONDS + 2}")
            appendLine("#EXT-X-MEDIA-SEQUENCE:${segments.firstOrNull()?.sequence ?: 0}")
            appendLine("#EXT-X-DISCONTINUITY-SEQUENCE:${segments.firstOrNull()?.piece ?: 0}")
            for ((i, segment) in segments.withIndex()) {
                if (i > 0 && segment.index == 0) appendLine("#EXT-X-DISCONTINUITY")
                appendLine("#EXT-X-PROGRAM-DATE-TIME:${segment.startsAt}")
                appendLine("#EXTINF:${"%.3f".format(Locale.ROOT, segment.seconds)},")
                appendLine("${segment.piece}-${segment.index}.ts")
            }
        }
    }
}
