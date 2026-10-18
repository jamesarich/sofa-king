package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.playback.remuxCommand
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.upstream.probeHeadersSafe
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/** What a recording run came to: the bytes now in the file, and the last reason the source could not be read, if any. */
data class RecordedTake(val bytes: Long, val lastFailure: String?)

/**
 * Records one channel into one file until a deadline. Each attempt opens an external playback session, the way the
 * HDHomeRun route does, so the recording holds a slot and is never evicted as idle; the session picks the channel's
 * healthiest source. FFmpeg stream-copies that source to MPEG-TS and the bytes are appended to the file, so a source
 * that drops mid-recording is reopened, on the next source if this one went bad, and the file carries on.
 */
class Recorder(
    private val ffmpeg: String,
    private val sessions: SessionManager,
    private val feeds: FeedService,
    private val clock: Clock,
    /** How long to wait before reopening a source that ended or could not be opened. */
    private val retryDelay: Duration = Duration.ofSeconds(5),
) {
    private val log = LoggerFactory.getLogger(Recorder::class.java)

    /** Appends [channelId]'s stream to [file] until [until]; cancelling stops it early with what was written. */
    suspend fun record(channelId: String, file: Path, until: Instant): RecordedTake {
        withContext(Dispatchers.IO) { Files.createDirectories(file.parent) }
        var failure: String? = null
        while (clock.instant() < until) {
            failure = try { attempt(channelId, file, until) } catch (e: CancellationException) { throw e } catch (e: ApiFailure) {
                e.code
            } catch (e: ProviderFailure) {
                "SOURCE_UNAVAILABLE"
            } catch (e: Exception) {
                log.warn("recording attempt failed channel={} class={} message=\"{}\"", channelId, e.javaClass.simpleName, redactedMessage(e))
                e.javaClass.simpleName
            } ?: failure
            val left = Duration.between(clock.instant(), until)
            if (left > Duration.ZERO) delay(minOf(left, retryDelay).toMillis())
        }
        return RecordedTake(withContext(Dispatchers.IO) { if (Files.exists(file)) Files.size(file) else 0L }, failure)
    }

    /** One session and one FFmpeg run; null when the source ended on its own, else why it could not be read. */
    private suspend fun attempt(channelId: String, file: Path, until: Instant): String? {
        val session = sessions.openExternal(channelId, CLIENT)
        try {
            sessions.attach(session.id, coroutineContext.job)
            val offering = sessions.offeringOf(session.id)
            val stream = feeds.playable(offering)
            feeds.upstream.check(URI(stream.url))
            val command = remuxCommand(ffmpeg, stream.url, stream.headers.takeIf(::probeHeadersSafe).orEmpty())
            val bytes = sessions.bytesOf(session.id)
            val (errors, process) = withContext(Dispatchers.IO) {
                val errors = Files.createTempFile("sofa-record-", ".log")
                errors to ProcessBuilder(command).redirectError(errors.toFile()).start()
            }
            var written = 0L
            try {
                coroutineScope {
                    // A blocked pipe read ignores cancellation; killing FFmpeg at the deadline or on cancel unblocks it.
                    val deadline = launch {
                        try { delay(Duration.between(clock.instant(), until).toMillis().coerceAtLeast(0)) } finally { process.destroy() }
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
                withContext(NonCancellable + Dispatchers.IO) {
                    process.destroy()
                    if (withTimeoutOrNull(2.seconds) { process.onExit().await() } == null) process.destroyForcibly()
                    Files.deleteIfExists(errors)
                }
            }
            return if (written == 0L) "NO_MEDIA" else null
        } finally {
            withContext(NonCancellable) { sessions.release(session.id) }
        }
    }

    companion object {
        /** The client the portal's Watching now shows for a recording's session. */
        const val CLIENT = "DVR"
    }
}
