package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.io.startOwned
import dev.jamesrich.sofaking.backend.io.terminate
import dev.jamesrich.sofaking.backend.playback.LoopbackRelay
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.playback.TsFanout
import dev.jamesrich.sofaking.backend.playback.UpstreamProtocolException
import dev.jamesrich.sofaking.backend.playback.remuxCommand
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.backend.upstream.ffmpegProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * What a recording run came to: the bytes now in the file, and the last reason the source could not be read, if any.
 */
data class RecordedTake(val bytes: Long, val lastFailure: String?)

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
    /** How long to wait before reopening a source that ended or could not be opened. */
    private val retryDelay: Duration = Duration.ofSeconds(5),
    /**
     * Joins an MPEG-TS source's one shared upstream connection, the one players of it read; null
     * records every source through FFmpeg.
     */
    private val shared: (suspend (Offering, ResolvedStream) -> TsFanout.Reader)? = null,
) {
    private val log = LoggerFactory.getLogger(Recorder::class.java)

    /** Appends [channelId]'s stream to [file] until [until]; cancelling stops it early with what was written. */
    suspend fun record(channelId: String, file: Path, until: Instant): RecordedTake {
        withContext(Dispatchers.IO) { Files.createDirectories(file.parent) }
        var failure: String? = null
        while (clock.instant() < until) {
            failure =
                try {
                    attempt(channelId, file, until)
                } catch (
                    e: CancellationException,
                ) {
                    throw e
                } catch (e: ApiFailure) {
                    e.code
                } catch (e: ProviderFailure) {
                    "SOURCE_UNAVAILABLE"
                } catch (e: Exception) {
                    log.warn(
                        "recording attempt failed channel={} class={} message=\"{}\"",
                        channelId,
                        e.javaClass.simpleName,
                        redactedMessage(e),
                    )
                    e.javaClass.simpleName
                } ?: failure
            val left = Duration.between(clock.instant(), until)
            if (left > Duration.ZERO) delay(minOf(left, retryDelay).toMillis())
        }
        return RecordedTake(withContext(Dispatchers.IO) { if (Files.exists(file)) Files.size(file) else 0L }, failure)
    }

    /**
     * One session and one read of its source; null when the source ended on its own, else why it could not be read.
     * The session holds only this attempt's job, so ending the session (a release, or its source going bad) ends the
     * attempt and the next one reopens, never the whole recording.
     */
    private suspend fun attempt(channelId: String, file: Path, until: Instant): String? {
        val session = sessions.openExternal(channelId, CLIENT, shareable = false)
        try {
            return try {
                coroutineScope {
                    sessions.attach(session.id, coroutineContext.job)
                    read(session.id, file, until)
                }
            } catch (e: CancellationException) {
                // Rethrown when the recording itself was cancelled; otherwise the session ended under this attempt.
                currentCoroutineContext().ensureActive()
                "SESSION_ENDED"
            }
        } finally {
            withContext(NonCancellable) { sessions.release(session.id) }
        }
    }

    /** Reads the session's source into [file] until [until], through the shared connection or FFmpeg. */
    private suspend fun read(sessionId: String, file: Path, until: Instant): String? {
        val offering = sessions.offeringOf(sessionId)
        val stream = feeds.playable(offering)
        feeds.upstream.check(URI(stream.url))
        if (shared != null && offering.protocol == "mpegts") {
            copyShared(shared, offering, stream, file, until, sessions.bytesOf(sessionId))?.let {
                return it.ifEmpty { null }
            }
        }
        val bytes = sessions.bytesOf(sessionId)
        return relay.reading(stream, offering.protocol) { source -> remux(source, file, until, bytes) }
    }

    /** FFmpeg's stream copy of [source] appended to [file] until [until]; "NO_MEDIA" when it wrote nothing. */
    private suspend fun remux(source: FfmpegSource, file: Path, until: Instant, bytes: AtomicLong): String? {
        val process = ffmpegProcess(
            remuxCommand(ffmpeg, source),
        ).redirectError(ProcessBuilder.Redirect.DISCARD).startOwned()
        var written = 0L
        try {
            coroutineScope {
                // A blocked pipe read ignores cancellation; killing FFmpeg at the deadline or on cancel unblocks it.
                val deadline = launch {
                    try {
                        delay(Duration.between(clock.instant(), until).toMillis().coerceAtLeast(0))
                    } finally {
                        process.destroy()
                    }
                }
                withContext(Dispatchers.IO) {
                    FileOutputStream(file.toFile(), true).use { out ->
                        val input = process.inputStream
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            bytes.addAndGet(read.toLong())
                        }
                    }
                }
                deadline.cancel()
            }
        } finally {
            process.terminate()
        }
        return if (written == 0L) "NO_MEDIA" else null
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
            try {
                reader.awaitStarted()
            } catch (e: UpstreamProtocolException) {
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return "SOURCE_UNAVAILABLE"
            }
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
    }
}
