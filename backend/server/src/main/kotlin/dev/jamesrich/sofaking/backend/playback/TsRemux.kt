package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.io.startOwned
import dev.jamesrich.sofaking.backend.io.terminate
import dev.jamesrich.sofaking.backend.upstream.ffmpegBase
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.slf4j.LoggerFactory

/**
 * FFmpeg remuxing one HLS source into MPEG-TS for a client that only takes TS (Plex through the HDHomeRun
 * emulation). Streams are copied, never transcoded, so CEA-608 captions ride through inside the video. The
 * process lives exactly as long as the response: a hang-up kills it and reaps it.
 */
class TsRemux(private val ffmpeg: String) {
    private val log = LoggerFactory.getLogger(TsRemux::class.java)
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun active(): Int = processes.size

    /** Streams [url] remuxed to `video/mp2t` into [call]; returns when the client hangs up or FFmpeg exits. [sent] is told each chunk's size as it goes to the player. */
    suspend fun stream(call: ApplicationCall, url: String, headers: Map<String, String>, sent: (Int) -> Unit = {}) {
        val command = remuxCommand(ffmpeg, url, headers)
        // Creating the log file and forking FFmpeg block, so they run on IO; a cancel there must not drop either.
        val errors = withContext(NonCancellable + Dispatchers.IO) { Files.createTempFile("sofa-remux-", ".log") }
        val process = try {
            ProcessBuilder(command).redirectError(errors.toFile()).startOwned()
        } catch (e: java.io.IOException) {
            withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(errors) }
            throw e
        }
        processes += process
        // FFmpeg closing its pipe is the source ending; anything else that stops the copy is the client leaving.
        var ended = false
        try {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytesWriter(contentType = ContentType.parse("video/mp2t")) {
                coroutineScope {
                    // A blocked pipe read ignores cancellation; killing the process is what unblocks it when the client leaves.
                    val watchdog = launch { try { awaitCancellation() } finally { process.destroy() } }
                    withContext(Dispatchers.IO) {
                        val input = process.inputStream
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            writeFully(buffer, 0, read)
                            sent(read)
                            flush()
                        }
                        // The watchdog killing FFmpeg for a client that left also ends the read; only a read still wanted is the source ending.
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
    private fun tail(errors: Path): String =
        redactedMessage(runCatching { Files.readAllLines(errors) }.getOrDefault(emptyList()).takeLast(3).joinToString(" | "))
}

/** The FFmpeg command that stream-copies [url] to MPEG-TS on its stdout. */
internal fun remuxCommand(ffmpeg: String, url: String, headers: Map<String, String>): List<String> {
    val command = ffmpegBase(ffmpeg, "warning", headers)
    // FFmpeg's own selection takes one video (highest resolution) and one audio (most channels) and skips the
    // other variants' downloads; subtitles and data have no reliable MPEG-TS mapping and are left out.
    command += listOf("-i", url, "-sn", "-dn", "-c", "copy", "-f", "mpegts", "-mpegts_flags", "+resend_headers", "-flush_packets", "1", "pipe:1")
    return command
}
