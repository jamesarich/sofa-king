package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.NS_PER_MS
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
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
class TsRemux(
    private val ffmpeg: String,
    private val relay: LoopbackRelay,
    /** `SOFA_QUALITY_FLOORS`: also replace a source that shows black or sends too little, not only one that stalls. */
    private val qualityFloors: Boolean = false,
    private val now: () -> Long = { System.nanoTime() / NS_PER_MS },
) {
    private val log = LoggerFactory.getLogger(TsRemux::class.java)
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun active(): Int = processes.size

    /**
     * Streams [stream] remuxed to `video/mp2t` into [call]; returns when the client hangs up or the source ends. [sent]
     * is told each chunk's size as it goes to the player. Given [next], a source that stalls, or with the quality
     * floors on shows black or sends too little, is replaced in the same response by the stream [next] answers for
     * the reason ([RemuxWatch.STALL], [RemuxWatch.BLACK] or [RemuxWatch.BITRATE]); null keeps the current one.
     */
    suspend fun stream(
        call: ApplicationCall,
        stream: ResolvedStream,
        sent: (Int) -> Unit = {},
        next: (suspend (String) -> ResolvedStream?)? = null,
    ) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondBytesWriter(contentType = ContentType.parse("video/mp2t")) {
            var current: ResolvedStream? = stream
            var floors = qualityFloors
            while (current != null) {
                val playing = current
                val stalls = StallWatch(now)
                val run = relay.reading(playing, "hls", stalls) { source ->
                    Run(source, stalls, floors).pump(this, sent, next)
                }
                current = run.switched
                // An FFmpeg that cannot check the picture (a source with no video) ends before its first byte; the
                // source plays again without the check.
                if (current == null && floors && run.empty) {
                    floors = false
                    current = playing
                }
            }
        }
    }

    /** One FFmpeg run on one source, and what ended it. */
    private inner class Run(private val source: FfmpegSource, private val stalls: StallWatch, floors: Boolean) {
        private val blackLog: Path? = if (floors) Files.createTempFile("sofa-black-", ".log") else null
        private val watch = RemuxWatch(stalls, floors, blackLog, now())
        private var bytes = 0L

        /** The stream that replaced this run's source; null when it ended or the client left. */
        @Volatile var switched: ResolvedStream? = null

        /** Whether FFmpeg sent nothing at all. */
        val empty: Boolean get() = bytes == 0L

        suspend fun pump(
            out: ByteWriteChannel,
            sent: (Int) -> Unit,
            next: (suspend (String) -> ResolvedStream?)?,
        ): Run {
            val command = remuxCommand(ffmpeg, source, blackLog)
            // Creating the log file and forking FFmpeg block, so they run on IO; a cancel there must not drop either.
            val errors = withContext(NonCancellable + Dispatchers.IO) { Files.createTempFile("sofa-remux-", ".log") }
            val process = runCatching { ffmpegProcess(command).redirectError(errors.toFile()).startOwned() }
                .onFailure { withContext(NonCancellable + Dispatchers.IO) { cleanUp(errors) } }
                .getOrThrow()
            processes += process
            // FFmpeg closing its pipe is the source ending; anything else that stops the copy is the client leaving.
            var ended = false
            try {
                coroutineScope {
                    // A blocked pipe read ignores cancellation; killing the process is what unblocks it when the
                    // client leaves.
                    val watchdog = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            process.destroy()
                        }
                    }
                    val monitor = next?.let { launch { monitor(process, it) } }
                    withContext(Dispatchers.IO) {
                        val input = process.inputStream
                        val buffer = ByteArray(64 * 1024)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            if (read > 0) {
                                out.writeFully(buffer, 0, read)
                                bytes += read
                                sent(read)
                                out.flush()
                            }
                            read = input.read(buffer)
                        }
                        // The watchdog killing FFmpeg for a client that left, or the monitor for a new source, also
                        // ends the read; only a read still wanted is the source ending.
                        ended = isActive && switched == null
                    }
                    monitor?.cancel()
                    watchdog.cancel()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    process.terminate(grace = 2.seconds)
                    processes -= process
                    val exit = if (process.isAlive) null else process.exitValue()
                    if (ended && exit != 0) log.warn("remux ended exit={} tail=\"{}\"", exit, tail(errors))
                    cleanUp(errors)
                }
            }
            return this
        }

        /**
         * Checks the run about once a second, and asks [next] for a new source once for each reason to leave this
         * one; the first answer stops FFmpeg, and the response goes on with it.
         */
        private suspend fun monitor(process: Process, next: suspend (String) -> ResolvedStream?) {
            val asked = HashSet<String>()
            while (switched == null) {
                delay(CHECK_MILLIS)
                val reason = withContext(Dispatchers.IO) { watch.reason(now(), bytes) }
                if (reason != null && asked.add(reason)) switched = next(reason)
            }
            // A signal, not Process.destroy, which would close the pipe under the read: FFmpeg exits and the read
            // sees its pipe end.
            process.toHandle().destroy()
        }

        private fun cleanUp(errors: Path) {
            Files.deleteIfExists(errors)
            blackLog?.let(Files::deleteIfExists)
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

/**
 * The FFmpeg command that stream-copies [source] to MPEG-TS on its stdout. Given [blackLog], it also decodes the
 * video's keyframes, the copy untouched, and writes where black starts and ends there.
 */
internal fun remuxCommand(ffmpeg: String, source: FfmpegSource, blackLog: Path? = null): List<String> {
    val command = ffmpegBase(ffmpeg, "warning", source)
    if (blackLog != null) command += listOf("-skip_frame", "nokey")
    // FFmpeg's own selection takes one video (highest resolution) and one audio (most channels) and skips the
    // other variants' downloads; subtitles and data have no reliable MPEG-TS mapping and are left out.
    command +=
        listOf(
            "-i", source.url, "-sn", "-dn", "-c", "copy", "-f", "mpegts", "-mpegts_flags", "+resend_headers",
            "-flush_packets", "1", "pipe:1",
        )
    if (blackLog != null) {
        command += listOf(
            "-map",
            "0:v:0?",
            "-an",
            "-vf",
            "blackdetect=d=0:pix_th=0.10,metadata=mode=print:file=${blackLog.toAbsolutePath()}",
            "-f",
            "null",
            "-",
        )
    }
    return command
}

/** How often a remux checks its source. */
private const val CHECK_MILLIS = 1000L

/** How many of FFmpeg's last error lines a failure reports. */
private const val ERROR_LINES = 3
