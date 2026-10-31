package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.io.startOwned
import dev.jamesrich.sofaking.backend.io.terminate
import dev.jamesrich.sofaking.backend.playback.LoopbackRelay
import dev.jamesrich.sofaking.backend.playback.PlaylistMarkers
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.playback.StallWatch
import dev.jamesrich.sofaking.backend.playback.SteadyPlaylists
import dev.jamesrich.sofaking.backend.playback.TsFanout
import dev.jamesrich.sofaking.backend.playback.UpstreamProtocolException
import dev.jamesrich.sofaking.backend.playback.remuxCommand
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.backend.upstream.ffmpegProcess
import dev.jamesrich.sofaking.contract.RecordingBreak
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * What a recording run came to: the bytes now in the file, the last reason the source could not be read, if any, and
 * the ad breaks the source's playlists marked, as offsets into what this run wrote.
 */
data class RecordedTake(val bytes: Long, val lastFailure: String?, val breaks: List<RecordingBreak> = emptyList())

/**
 * Records one channel into one file until a deadline. Each attempt opens an external playback session, the way the
 * HDHomeRun route does, so the recording holds a slot and is never evicted as idle; the session picks the channel's
 * healthiest source. FFmpeg stream-copies that source, read through [relay], to MPEG-TS and the bytes are appended to
 * the file, so a source that drops mid-recording is reopened, on the next source if this one went bad, and the file
 * carries on.
 */
class Recorder internal constructor(
    private val ffmpeg: String,
    private val sessions: SessionManager,
    private val feeds: FeedService,
    private val clock: Clock,
    /** The relay FFmpeg reads the source through. */
    private val relay: LoopbackRelay,
    /** The longest to wait before reopening a source that failed; one that ended or sent nothing is reopened sooner. */
    private val retryDelay: Duration = RETRY_DELAY,
    /**
     * Joins an MPEG-TS source's one shared upstream connection, the one players of it read; null
     * records every source through FFmpeg.
     */
    private val shared: (suspend (Offering, ResolvedStream) -> TsFanout.Reader)? = null,
    /** How long FFmpeg may write nothing before its source counts as stalled and is reopened. */
    private val stallAfter: Duration = STALL_AFTER,
) {
    private val log = LoggerFactory.getLogger(Recorder::class.java)

    /** Appends [channelId]'s stream to [file] until [until]; canceling stops it early with what was written. */
    suspend fun record(channelId: String, file: Path, until: Instant): RecordedTake {
        withContext(Dispatchers.IO) { Files.createDirectories(file.parent) }
        var failure: String? = null
        val markers = PlaylistMarkers()
        val reopen = ReopenDelay(retryDelay)
        var outcome: String?
        while (clock.instant() < until) {
            outcome =
                runCatchingCancellable { attempt(channelId, file, until, markers) }.getOrElse { e ->
                    when (e) {
                        is ApiFailure -> e.code

                        is ProviderFailure -> "SOURCE_UNAVAILABLE"

                        else -> {
                            log.warn(
                                "recording attempt failed channel={} class={} message=\"{}\"",
                                channelId,
                                e.javaClass.simpleName,
                                redactedMessage(e),
                            )
                            e.javaClass.simpleName
                        }
                    }
                }
            failure = outcome ?: failure
            val left = Duration.between(clock.instant(), until)
            if (left > Duration.ZERO) delay(minOf(left, reopen.after(outcome)).toMillis())
        }
        val bytes = withContext(Dispatchers.IO) { if (Files.exists(file)) Files.size(file) else 0L }
        return RecordedTake(bytes, failure, markers.breaks())
    }

    /**
     * One session and one read of its source; null when the source ended on its own, else why it could not be read.
     * The session holds only this attempt's job, so ending the session (a release, or its source going bad) ends the
     * attempt and the next one reopens, never the whole recording.
     */
    private suspend fun attempt(channelId: String, file: Path, until: Instant, markers: PlaylistMarkers): String? {
        val session = sessions.external.open(channelId, CLIENT, shareable = false)
        // Taken while the session is up: most reads end because it went, and the table then no longer answers for it.
        val counter = sessions.media.bytesOf(session.id)
        try {
            return try {
                coroutineScope {
                    sessions.media.attach(session.id, coroutineContext.job)
                    read(session.id, file, until, markers)
                }.also { readEnded(channelId, counter, it ?: "SOURCE_ENDED") }
            } catch (_: CancellationException) {
                // Rethrown when the recording itself was canceled, as it is at its deadline, which ends the last read;
                // otherwise the session ended under this attempt.
                val canceled = !currentCoroutineContext().isActive
                readEnded(channelId, counter, if (canceled) "CANCELED" else "SESSION_ENDED")
                currentCoroutineContext().ensureActive()
                "SESSION_ENDED"
            }
        } finally {
            withContext(NonCancellable) { sessions.release(session.id) }
        }
    }

    /** The only trace of why a recording that came up short ended each of its reads, and of what the read appended. */
    private fun readEnded(channelId: String, bytes: AtomicLong, reason: String) {
        log.info("recording read ended channel={} bytes={} reason={}", channelId, bytes.get(), reason)
    }

    /** Reads the session's source into [file] until [until], through the shared connection or FFmpeg. */
    private suspend fun read(sessionId: String, file: Path, until: Instant, markers: PlaylistMarkers): String? {
        val offering = sessions.media.offeringOf(sessionId)
        var hls = offering.protocol == "hls"
        // Only a playlist says how long the media is; a source without one leaves later offsets unknown.
        if (hls) markers.begin() else markers.freeze()
        val stream = feeds.playable(offering)
        feeds.upstream.check(URI(stream.url))
        if (shared != null && offering.protocol == "mpegts") {
            copyShared(shared, offering, stream, file, until, sessions.media.bytesOf(sessionId))?.let {
                return it.ifEmpty { null }
            }
            // The body was a playlist after all, so FFmpeg reads it as one: its markers count and its lapses show.
            hls = true
            markers.begin()
        }
        val bytes = sessions.media.bytesOf(sessionId)
        // A lapse ends FFmpeg and the next session starts clean; only a numbering that starts over is carried on.
        val steady = if (hls) SteadyPlaylists(holdLapses = false) else null
        val stalls = if (hls) StallWatch() else null
        return relay.reading(stream, offering.protocol, stalls = stalls, markers = markers, steady = steady) { source ->
            remux(source, file, until, bytes, steady, stalls)
        }
    }

    /**
     * FFmpeg's stream copy of [source] appended to [file] until [until]; "NO_MEDIA" when it wrote nothing. A lapse in
     * any playlist [steady] saw, or a playlist [stalls] saw stop advancing, ends it as a stall does: FFmpeg lives on
     * the playlists still advancing, such as a separate audio rendition, and writes the stream without its picture at
     * a rate the write watchdog never sees. Destroying FFmpeg closes its output under the blocked read, which then
     * throws "Stream closed"; that is the end of the read, not a failure of it.
     */
    private suspend fun remux(
        source: FfmpegSource,
        file: Path,
        until: Instant,
        bytes: AtomicLong,
        steady: SteadyPlaylists?,
        stalls: StallWatch?,
    ): String? {
        // FFmpeg's own account of the read, logged when it ends: the only view of what the source served it.
        val errors = withContext(Dispatchers.IO) { Files.createTempFile("sofa-recording-", ".log") }
        val process = ffmpegProcess(
            remuxCommand(ffmpeg, source),
        ).redirectError(errors.toFile()).startOwned()
        var written = 0L
        val stalled = AtomicBoolean()
        val destroyed = AtomicBoolean()
        val lastWrite = AtomicReference(clock.instant())
        try {
            coroutineScope {
                // A blocked pipe read ignores cancellation; killing FFmpeg at the deadline or on cancel unblocks it.
                val deadline = launch {
                    try {
                        delay(Duration.between(clock.instant(), until).toMillis().coerceAtLeast(0))
                    } finally {
                        destroyed.set(true)
                        process.destroy()
                    }
                }
                // FFmpeg that is alive and writes nothing is waiting on a source that will not answer it: reopen.
                val watchdog = launch {
                    while (!stalled.get()) {
                        delay(STALL_CHECK.toMillis())
                        if (steady?.lapsed == true || stalls?.tripped == true ||
                            Duration.between(lastWrite.get(), clock.instant()) > stallAfter
                        ) {
                            stalled.set(true)
                            destroyed.set(true)
                            process.destroy()
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    FileOutputStream(file.toFile(), true).use { out ->
                        val input = process.inputStream
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.readUnlessDestroyed(buffer, destroyed)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            lastWrite.set(clock.instant())
                            bytes.addAndGet(read.toLong())
                        }
                    }
                }
                deadline.cancel()
                watchdog.cancel()
            }
        } finally {
            process.terminate()
            logFfmpeg(errors, file)
        }
        return when {
            stalled.get() -> "SOURCE_STALLED"
            written == 0L -> "NO_MEDIA"
            else -> null
        }
    }

    /** Logs the last lines FFmpeg wrote to [errors] about its read of [file], then removes the file. */
    private suspend fun logFfmpeg(errors: Path, file: Path) = withContext(NonCancellable + Dispatchers.IO) {
        val tail = runCatching { Files.readAllLines(errors) }.getOrDefault(emptyList())
            .takeLast(FFMPEG_LINES).joinToString(" | ")
        Files.deleteIfExists(errors)
        // Segment URLs can carry tokens, so the lines go through the same redaction as other FFmpeg output.
        if (tail.isNotEmpty()) {
            log.info(
                "recording ffmpeg file=\"{}\" last=\"{}\"",
                file.fileName,
                redactedMessage(tail),
            )
        }
    }

    /**
     * Appends the source's shared MPEG-TS connection to [file] until [until], so a recording of a channel someone
     * watches opens no second connection; the packets are already MPEG-TS, so nothing remuxes them. Returns "" when
     * the source ended on its own, a failure reason, or null when the body is not MPEG-TS after
     * all and FFmpeg should read it.
     */
    private suspend fun copyShared(
        join: suspend (Offering, ResolvedStream) -> TsFanout.Reader,
        offering: Offering,
        stream: ResolvedStream,
        file: Path,
        until: Instant,
        bytes: AtomicLong,
    ): String? {
        val reader = join(offering, stream)
        try {
            // A body in the other protocol counts as the source ending; any other failure to start is the source's.
            runCatchingCancellable { reader.awaitStarted() }
                .onFailure { return if (it is UpstreamProtocolException) null else "SOURCE_UNAVAILABLE" }
            var written = 0L
            withTimeoutOrNull(Duration.between(clock.instant(), until).toMillis().coerceAtLeast(0)) {
                withContext(Dispatchers.IO) {
                    FileOutputStream(file.toFile(), true).use { out ->
                        for (chunk in reader.chunks) {
                            out.write(chunk)
                            written += chunk.size
                            bytes.addAndGet(chunk.size.toLong())
                        }
                    }
                }
            }
            return if (written == 0L) "NO_MEDIA" else ""
        } finally {
            withContext(NonCancellable) { reader.close() }
        }
    }

    companion object {
        /** The client the portal's Watching now shows for a recording's session. */
        const val CLIENT = "DVR"
        private val RETRY_DELAY: Duration = Duration.ofSeconds(5)

        /** Long enough for FFmpeg to open a slow source and fill a first segment, short of a minute of lost media. */
        private val STALL_AFTER: Duration = Duration.ofSeconds(30)
        private val STALL_CHECK: Duration = Duration.ofSeconds(1)

        /** How many of FFmpeg's last lines a read's log line keeps. */
        private const val FFMPEG_LINES = 4
    }
}

/**
 * Reads FFmpeg's output; once the recorder destroyed the process, a read it cut short by closing the stream ends the
 * output like the end of the stream does.
 */
internal fun InputStream.readUnlessDestroyed(buffer: ByteArray, destroyed: AtomicBoolean): Int = try {
    read(buffer)
} catch (e: IOException) {
    if (destroyed.get()) -1 else throw e
}
