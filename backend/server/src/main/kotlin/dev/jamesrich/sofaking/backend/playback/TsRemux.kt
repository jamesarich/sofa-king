package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.io.startOwned
import dev.jamesrich.sofaking.backend.io.terminate
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.backend.upstream.ffmpegBase
import dev.jamesrich.sofaking.backend.upstream.ffmpegProcess
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * FFmpeg remuxing one HLS source into MPEG-TS for a client that only takes TS (Plex through the HDHomeRun
 * emulation). Streams are copied, never transcoded, so CEA-608 captions ride through inside the video. FFmpeg reads
 * the source through [relay], which checks every hop. The process lives exactly as long as the response: a hang-up
 * kills it and reaps it.
 */
class TsRemux(private val ffmpeg: String, private val relay: LoopbackRelay) {
    private val log = LoggerFactory.getLogger(TsRemux::class.java)
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun active(): Int = processes.size

    /**
     * Streams [stream] remuxed to `video/mp2t` into [call]; returns when the client hangs up or FFmpeg exits. [sent] is
     * told each chunk's size as it goes to the player.
     */
    suspend fun stream(call: ApplicationCall, stream: ResolvedStream, sent: (Int) -> Unit = {}) =
        relay.reading(stream, "hls") { source -> stream(call, source, sent) }

    private suspend fun stream(call: ApplicationCall, source: FfmpegSource, sent: (Int) -> Unit) {
        val command = remuxCommand(ffmpeg, source)
        // Creating the log file and forking FFmpeg block, so they run on IO; a cancel there must not drop either.
        val errors = withContext(NonCancellable + Dispatchers.IO) { Files.createTempFile("sofa-remux-", ".log") }
        val process = runCatching { ffmpegProcess(command).redirectError(errors.toFile()).startOwned() }
            .onFailure { withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(errors) } }
            .getOrThrow()
        processes += process
        // FFmpeg closing its pipe is the source ending; anything else that stops the copy is the client leaving.
        var ended = false
        try {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytesWriter(contentType = ContentType.parse("video/mp2t")) {
                coroutineScope {
                    // A blocked pipe read ignores cancellation; killing the process is what
                    // unblocks it when the client leaves.
                    val watchdog = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            process.destroy()
                        }
                    }
                    withContext(Dispatchers.IO) {
                        val input = process.inputStream
                        val buffer = ByteArray(64 * 1024)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            if (read > 0) {
                                writeFully(buffer, 0, read)
                                sent(read)
                                flush()
                            }
                            read = input.read(buffer)
                        }
                        // The watchdog killing FFmpeg for a client that left also ends the read; only a read
                        // still wanted is the source ending.
                        ended = isActive
                    }
                    watchdog.cancel()
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process.terminate(grace = 2.seconds)
                processes -= process
                val exit = if (process.isAlive) null else process.exitValue()
                if (ended && exit != 0) log.warn("remux ended exit={} tail=\"{}\"", exit, tail(errors))
                Files.deleteIfExists(errors)
            }
        }
    }

    /** Kills every running remux; the server is shutting down. */
    fun close() {
        processes.forEach { it.destroyForcibly() }
        processes.forEach { it.waitFor(2, TimeUnit.SECONDS) }
        processes.clear()
    }

    /** The last FFmpeg lines with URLs removed, since segment URLs can carry tokens. */
    private fun tail(errors: Path): String = redactedMessage(
        runCatching {
            Files.readAllLines(errors)
        }.getOrDefault(emptyList()).takeLast(ERROR_LINES).joinToString(" | "),
    )
}

/** The FFmpeg command that stream-copies [source] to MPEG-TS on its stdout. */
internal fun remuxCommand(ffmpeg: String, source: FfmpegSource): List<String> {
    val command = ffmpegBase(ffmpeg, "warning", source)
    // FFmpeg's own selection takes one video (highest resolution) and one audio (most channels) and skips the
    // other variants' downloads; subtitles and data have no reliable MPEG-TS mapping and are left out.
    command +=
        listOf(
            "-i", source.url, "-sn", "-dn", "-c", "copy", "-f", "mpegts", "-mpegts_flags", "+resend_headers",
            "-flush_packets", "1", "pipe:1",
        )
    return command
}

/** How many of FFmpeg's last error lines a failure reports. */
private const val ERROR_LINES = 3
