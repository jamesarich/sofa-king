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
    /** How long to wait before reopening a source that ended or could not be opened. */
    private val retryDelay: Duration = RETRY_DELAY,
    /**
     * Joins an MPEG-TS source's one shared upstream connection, the one players of it read; null
     * records every source through FFmpeg.
     */
    private val shared: (suspend (Offering, ResolvedStream) -> TsFanout.Reader)? = null,
) {
    private val log = LoggerFactory.getLogger(Recorder::class.java)

    /** Appends [channelId]'s stream to [file] until [until]; canceling stops it early with what was written. */
    suspend fun record(channelId: String, file: Path, until: Instant): RecordedTake {
        withContext(Dispatchers.IO) { Files.createDirectories(file.parent) }
        var failure: String? = null
        val markers = PlaylistMarkers()
        while (clock.instant() < until) {
            failure =
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
                } ?: failure
            val left = Duration.between(clock.instant(), until)
            if (left > Duration.ZERO) delay(minOf(left, retryDelay).toMillis())
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
        try {
            return try {
                coroutineScope {
                    sessions.media.attach(session.id, coroutineContext.job)
                    read(session.id, file, until, markers)
                }
            } catch (_: CancellationException) {
                // Rethrown when the recording itself was canceled; otherwise the session ended under this attempt.
                currentCoroutineContext().ensureActive()
                "SESSION_ENDED"
            }
        } finally {
            withContext(NonCancellable) { sessions.release(session.id) }
        }
    }

    /** Reads the session's source into [file] until [until], through the shared connection or FFmpeg. */
    private suspend fun read(sessionId: String, file: Path, until: Instant, markers: PlaylistMarkers): String? {
        val offering = sessions.media.offeringOf(sessionId)
        // Only a playlist says how long the media is; a source without one leaves later offsets unknown.
        if (offering.protocol == "hls") markers.begin() else markers.freeze()
        val stream = feeds.playable(offering)
        feeds.upstream.check(URI(stream.url))
        if (shared != null && offering.protocol == "mpegts") {
            copyShared(shared, offering, stream, file, until, sessions.media.bytesOf(sessionId))?.let {
                return it.ifEmpty { null }
            }
        }
        val bytes = sessions.media.bytesOf(sessionId)
        return relay.reading(stream, offering.protocol, markers = markers) { source ->
            remux(source, file, until, bytes)
        }
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
    }
}
