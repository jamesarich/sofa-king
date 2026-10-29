package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.io.startOwned
import dev.jamesrich.sofaking.backend.io.terminate
import dev.jamesrich.sofaking.backend.library.VodRunner
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.upstream.ffmpegProcess
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * MPEG-TS sources as HLS, for a player that plays nothing else (AVPlayer, hls.js). One FFmpeg per session copies the
 * source's shared connection, as [join] hands it out, into a live HLS playlist under [directory]: the streams are
 * copied, never converted, so it costs little and CEA-608 rides through inside the video. A package lives as long as
 * its session's epoch: the session holds its job, so a release, an expiry or a move to another source ends it, and
 * the next playlist request at the new epoch starts another.
 */
internal class PackagedStreams(
    private val ffmpeg: String,
    private val directory: Path,
    private val scope: CoroutineScope,
    private val sessions: SessionMedia,
    private val join: suspend (Offering, ResolvedStream) -> TsFanout.Reader,
) {
    private val log = LoggerFactory.getLogger(PackagedStreams::class.java)
    private val packages = ConcurrentHashMap<String, Package>()

    init {
        // Packages never outlive the server; what an earlier run left is gone.
        VodRunner.clear(directory)
    }

    /** Packages running now, each one FFmpeg. */
    val running: Int get() = packages.size

    private inner class Package(val id: String, val epoch: Int, val dir: Path) {
        /** Completes with null once FFmpeg runs, or with the failure that kept it from starting. */
        val started = CompletableDeferred<ApiFailure?>()

        /** Whether a playlist has gone out, after which none waits for segments. */
        @Volatile var served = false

        /** Set before the package is shared. */
        lateinit var job: Job
    }

    /**
     * The live playlist of session [id] at [epoch], packaged from [stream] of [offering], with each segment named
     * through [segmentPath]. The first one waits until it lists [START_SEGMENTS] segments, as AVPlayer starts a live
     * stream no nearer its end than that, but no longer than [START_WAIT], inside AVPlayer's own 20 second timeout.
     */
    suspend fun playlist(
        id: String,
        epoch: Int,
        offering: Offering,
        stream: ResolvedStream,
        segmentPath: suspend (String) -> String,
    ): String {
        val pack = packageOf(id, epoch, offering, stream)
        pack.started.await()?.let { throw it }
        val file = pack.dir.resolve(PLAYLIST)
        if (!pack.served) {
            withTimeoutOrNull(START_WAIT.toMillis()) {
                while (segments(file) < START_SEGMENTS && pack.job.isActive) delay(POLL_MS)
            }
            pack.served = true
        }
        val text = withContext(Dispatchers.IO) { if (Files.exists(file)) Files.readString(file) else null }
            ?: throw unavailable()
        val lines = text.lines().toMutableList()
        for (index in lines.indices) {
            if (SEGMENT.matches(lines[index])) lines[index] = segmentPath(SCHEME + lines[index])
        }
        return lines.joinToString("\n")
    }

    /** The segment file a [playlist] named as [url], or null when it is not one, or no longer there. */
    fun segment(id: String, epoch: Int, url: String): Path? {
        val name = url.removePrefix(SCHEME).takeIf { url.startsWith(SCHEME) && SEGMENT.matches(it) } ?: return null
        val pack = packages[id]?.takeIf { it.epoch == epoch } ?: return null
        return pack.dir.resolve(name).takeIf { Files.isRegularFile(it) }
    }

    /** Whether the newest segment of session [id]'s package at [epoch] carries CEA-608 captions. */
    suspend fun carriesCaptions(id: String, epoch: Int): Boolean = withContext(Dispatchers.IO) {
        val pack = packages[id]?.takeIf { it.epoch == epoch } ?: return@withContext false
        val newest = runCatchingCancellable {
            Files.list(pack.dir).use { files ->
                files.filter { SEGMENT.matches(it.fileName.toString()) }
                    .max(compareBy { it.fileName.toString().filter(Char::isDigit).toLong() }).orElse(null)
            }
        }.getOrNull() ?: return@withContext false
        val scan = CaptionScan()
        runCatchingCancellable { Files.newInputStream(newest).use { scan.feed(it.readNBytes(SCAN_LIMIT)) } }
        scan.found
    }

    private suspend fun packageOf(id: String, epoch: Int, offering: Offering, stream: ResolvedStream): Package {
        while (true) {
            val current = packages[id]
            if (current != null && current.epoch == epoch && !current.job.isCompleted) return current
            val pack = Package(id, epoch, directory.resolve(id).resolve("$epoch"))
            pack.job = scope.launch(Dispatchers.IO + CoroutineName("package-$id"), CoroutineStart.LAZY) {
                run(pack, offering, stream)
            }
            val won = if (current ==
                null
            ) {
                packages.putIfAbsent(id, pack) == null
            } else {
                packages.replace(id, current, pack)
            }
            if (!won) {
                pack.job.cancel()
                continue
            }
            current?.job?.cancel()
            pack.job.start()
            return pack
        }
    }

    private suspend fun run(pack: Package, offering: Offering, stream: ResolvedStream) {
        try {
            // The session holds the package, so whatever ends the session's requests ends it too.
            sessions.attach(pack.id, pack.job)
            val reader = join(offering, stream)
            try {
                copy(pack, reader)
            } finally {
                withContext(NonCancellable) { reader.close() }
            }
        } catch (e: ApiFailure) {
            pack.started.complete(e)
        } catch (e: IOException) {
            // FFmpeg ended, so its input closed; the next playlist request starts another.
            log.info("package ended session={} class={}", pack.id, e.javaClass.simpleName)
        } finally {
            pack.started.complete(unavailable())
            packages.remove(pack.id, pack)
            withContext(NonCancellable + Dispatchers.IO) { VodRunner.clear(pack.dir) }
        }
    }

    /**
     * Feeds [reader]'s packets to an FFmpeg that writes [pack]'s playlist, until either ends. The packets queue while
     * FFmpeg starts, which on a busy host outlasts the reader's own buffer.
     */
    private suspend fun copy(pack: Package, reader: TsFanout.Reader) = coroutineScope {
        val begun = runCatchingCancellable { reader.awaitStarted() }
        begun.exceptionOrNull()?.let { e ->
            // A body in the other protocol moves the session to it, and the player re-reads the descriptor.
            if (e is UpstreamProtocolException) sessions.protocolObserved(pack.id, pack.epoch, e.protocol, pack.job)
            throw unavailable()
        }
        val queue = Channel<ByteArray>(QUEUE_CHUNKS)
        val drain = launch {
            val outcome = runCatchingCancellable { for (chunk in reader.chunks) queue.send(chunk) }
            queue.close(outcome.exceptionOrNull())
        }
        Files.createDirectories(pack.dir)
        val process = ffmpegProcess(command(pack.dir)).redirectErrorStream(true)
            .redirectOutput(pack.dir.resolve("ffmpeg.log").toFile()).startOwned()
        pack.started.complete(null)
        try {
            process.outputStream.use { input ->
                for (chunk in queue) input.write(chunk)
            }
        } finally {
            drain.cancel()
            process.terminate()
        }
    }

    private fun command(dir: Path): List<String> = listOf(
        ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-f", "mpegts", "-i", "pipe:0",
        "-map", "0:v:0?", "-map", "0:a?", "-c", "copy", "-sn", "-dn",
        "-f", "hls", "-hls_time", "$SEGMENT_SECONDS", "-hls_list_size", "$LIST_SIZE",
        "-hls_flags", "delete_segments+temp_file+independent_segments",
        "-hls_segment_type", "mpegts", "-hls_segment_filename", dir.resolve("seg%d.ts").toString(),
        dir.resolve(PLAYLIST).toString(),
    )

    private suspend fun segments(file: Path): Int = withContext(Dispatchers.IO) {
        if (Files.exists(file)) Files.readAllLines(file).count { SEGMENT.matches(it) } else 0
    }

    private fun unavailable() =
        ApiFailure(HttpStatusCode.BadGateway, "UPSTREAM_UNAVAILABLE", "This channel isn't available right now", true)

    companion object {
        /** What a packaged segment's URL starts with among a session's resources, before the segment's file name. */
        const val SCHEME = "sofa-package:/"

        private const val PLAYLIST = "index.m3u8"
        private const val SEGMENT_SECONDS = 2
        private const val LIST_SIZE = 10
        private const val START_SEGMENTS = 3
        private val START_WAIT: Duration = Duration.ofSeconds(15)
        private const val POLL_MS = 200L
        private const val QUEUE_CHUNKS = 1024
        private const val SCAN_LIMIT = 16 * 1024 * 1024
        private val SEGMENT = Regex("seg[0-9]{1,9}\\.ts")
    }
}
