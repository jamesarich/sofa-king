package dev.jamesrich.sofaking.backend.multiview

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.io.terminate
import dev.jamesrich.sofaking.backend.library.HardwareAccel
import dev.jamesrich.sofaking.backend.library.VideoEncoder
import dev.jamesrich.sofaking.backend.library.VodRunner
import dev.jamesrich.sofaking.backend.playback.ChannelTap
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.backend.upstream.ffmpegProcess
import dev.jamesrich.sofaking.contract.MultiviewSession
import dev.jamesrich.sofaking.contract.OpenMultiview
import dev.jamesrich.sofaking.contract.PlaybackDescriptor
import dev.jamesrich.sofaking.contract.UpdateMultiview
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** `SOFA_MULTIVIEW`: whether the server composites multiview, only with a GPU encoder, or never. */
enum class MultiviewMode(val setting: String) {
    OFF("off"),
    AUTO("auto"),
    ON("on"),
    ;

    companion object {
        /** The mode [value] names, [AUTO] when it is unset. */
        fun parse(value: String?): MultiviewMode {
            val wanted = value?.trim()?.lowercase()?.ifEmpty { null } ?: return AUTO
            return entries.firstOrNull { it.setting == wanted }
                ?: throw IllegalArgumentException("SOFA_MULTIVIEW must be one of off, auto, on")
        }
    }
}

/** The picture a multiview draws: its [width] by [height], each tile a quarter of it. */
enum class MultiviewCanvas(val width: Int, val height: Int) {
    /** With a GPU encoder. */
    FULL(VideoEncoder.CHANNEL_WIDTH, VideoEncoder.CHANNEL_HEIGHT),

    /** In software, which a 1080p encode of four decodes would starve. */
    SMALL(SMALL_WIDTH, SMALL_HEIGHT),
    ;

    val tileWidth: Int get() = width / 2
    val tileHeight: Int get() = height / 2
}

private const val SMALL_WIDTH = 1280
private const val SMALL_HEIGHT = 720

/**
 * Multiview: two to four lineup channels drawn by one FFmpeg as one live HLS stream, which a player plays like a
 * channel. Each tile reads its channel through [tap], so it holds a playback session; the stream counts toward
 * `SOFA_MAX_TRANSCODES` through [mayStart]. With [MultiviewMode.AUTO] it is offered only when [encoder] is a GPU's, as
 * four decodes and an encode in software starve the recorder; [MultiviewMode.ON] draws it smaller in software. A
 * session lasts [LEASE] past its last renewal or media request; its files are under [directory].
 */
class MultiviewSessions(
    private val ffmpeg: String,
    private val directory: Path,
    private val lineup: LineupService,
    private val tap: ChannelTap,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val mode: MultiviewMode,
    private val encoder: () -> VideoEncoder,
    private val mayStart: suspend () -> Boolean = { true },
) {
    private val log = LoggerFactory.getLogger(MultiviewSessions::class.java)
    private val sessions = ConcurrentHashMap<String, Session>()

    /** Multiview streams playing now, each one FFmpeg. */
    val running: Int get() = sessions.size

    /** The picture size multiview draws at now, or null when it is not offered. */
    val canvas: MultiviewCanvas?
        get() = when {
            mode == MultiviewMode.OFF -> null
            encoder().accel != HardwareAccel.NONE -> MultiviewCanvas.FULL
            mode == MultiviewMode.ON -> MultiviewCanvas.SMALL
            else -> null
        }

    /** Why multiview is or is not offered, for the portal. */
    val verdict: String
        get() = when (canvas) {
            MultiviewCanvas.FULL -> "On, 1080p with the ${encoder().accel.setting} encoder"
            MultiviewCanvas.SMALL -> "On, 720p in software (SOFA_MULTIVIEW=on)"
            null -> if (mode == MultiviewMode.OFF) "Off (SOFA_MULTIVIEW=off)" else "Off: no GPU encoder works"
        }

    suspend fun open(request: OpenMultiview): MultiviewSession {
        val canvas = canvas ?: throw ApiFailure(
            HttpStatusCode.ServiceUnavailable,
            "MULTIVIEW_UNAVAILABLE",
            "This server does not composite multiview",
        )
        val channels = request.channelIds
        if (channels.size !in 2..Wire.MULTIVIEW_MAX_TILES || channels.distinct().size != channels.size) {
            invalid("Name 2 to ${Wire.MULTIVIEW_MAX_TILES} different channels")
        }
        if (request.audio !in channels.indices) invalid("audio is the index of one of the channels")
        channels.firstOrNull { lineup.entry(it)?.enabled != true }?.let {
            throw ApiFailure(HttpStatusCode.NotFound, "CHANNEL_NOT_FOUND", "No such channel")
        }
        if (!mayStart()) {
            throw ApiFailure(
                HttpStatusCode.ServiceUnavailable,
                Wire.ErrorCode.CAPACITY_EXHAUSTED,
                "Too many streams are being converted; try again when one stops",
                true,
            )
        }
        val session = Session(UUID.randomUUID().toString(), secret(), channels, canvas)
        sessions[session.id] = session
        session.start(request.audio)
        log.info("multiview opened id={} tiles={} canvas={}", session.id, channels.size, canvas)
        return session.wire()
    }

    fun get(id: String): MultiviewSession = session(id).wire()

    /** Renews session [id]; another audio tile restarts its stream there. */
    suspend fun update(id: String, update: UpdateMultiview): MultiviewSession {
        val session = session(id)
        val audio = update.audio
        if (audio != null && audio !in session.channels.indices) invalid("audio is the index of one of the channels")
        session.renew()
        if (audio != null && audio != session.audio) session.restart(audio)
        return session.wire()
    }

    suspend fun release(id: String) {
        sessions.remove(id)?.stop() ?: throw missing()
        log.info("multiview released id={}", id)
    }

    /** Stops the sessions nobody renewed or read within [LEASE]. */
    suspend fun expire() {
        val now = clock.instant()
        for (session in sessions.values.filter { it.expires < now }) {
            if (sessions.remove(session.id, session)) {
                session.stop()
                log.info("multiview expired id={}", session.id)
            }
        }
    }

    /**
     * The file [name] of session [id]'s stream, with its media type, or null for no such file; a request renews the
     * session. The playlist waits a little for FFmpeg's first segments.
     */
    suspend fun media(id: String, epoch: Int, secret: String, name: String): Pair<Path, String>? {
        val session = sessions[id]?.takeIf { it.secret == secret && it.epoch == epoch } ?: return null
        if (!NAME.matches(name)) return null
        session.renew()
        val file = session.dir().resolve(name)
        if (name == MULTIVIEW_PLAYLIST) {
            withTimeoutOrNull(START_WAIT.toMillis()) {
                while (!Files.exists(file)) delay(POLL_MS)
            }
        }
        if (!Files.exists(file)) return null
        return file to if (name.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
    }

    suspend fun close() {
        for (id in sessions.keys.toList()) sessions.remove(id)?.stop()
    }

    private fun session(id: String): Session = sessions[id] ?: throw missing()

    private inner class Session(
        val id: String,
        val secret: String,
        val channels: List<String>,
        val canvas: MultiviewCanvas,
    ) {
        @Volatile var expires: Instant = clock.instant().plus(LEASE)

        @Volatile var audio = 0

        @Volatile var epoch = 0
        private var job: Job? = null

        fun dir(): Path = directory.resolve(id).resolve("$epoch")

        fun renew() {
            expires = clock.instant().plus(LEASE)
        }

        fun start(tile: Int) {
            audio = tile
            epoch++
            val dir = dir()
            job = scope.launch(Dispatchers.IO + CoroutineName("multiview-$id")) { play(dir, tile) }
        }

        suspend fun restart(tile: Int) {
            val old = dir()
            job?.let {
                it.cancel()
                it.join()
            }
            VodRunner.clear(old)
            start(tile)
        }

        suspend fun stop() {
            job?.let {
                it.cancel()
                it.join()
            }
            withContext(Dispatchers.IO) { directory.resolve(id).toFile().deleteRecursively() }
        }

        /** Draws the tiles into [dir] until stopped, starting FFmpeg again a moment after it ends. */
        private suspend fun play(dir: Path, tile: Int) {
            Files.createDirectories(dir)
            while (coroutineContext.isActive) {
                runCatchingCancellable { tiles(channels, emptyList()) { draw(it, tile, dir) } }
                    .onFailure { log.warn("multiview failed id={} class={}", id, it.javaClass.simpleName) }
                delay(RETRY_MS)
            }
        }

        /** Opens each of [left]'s channels in turn, a black tile for one that will not, then runs [block] on all. */
        private suspend fun tiles(
            left: List<String>,
            open: List<FfmpegSource?>,
            block: suspend (List<FfmpegSource?>) -> Unit,
        ) {
            if (left.isEmpty()) return block(open)
            var ran = false
            runCatchingCancellable {
                tap.reading(left.first(), CLIENT) { source ->
                    ran = true
                    tiles(left.drop(1), open + source, block)
                }
            }.onFailure { if (ran) throw it }
            if (!ran) tiles(left.drop(1), open + null, block)
        }

        private suspend fun draw(tiles: List<FfmpegSource?>, tile: Int, dir: Path) {
            val command = multiviewCommand(ffmpeg, tiles, tile, canvas, encoder(), dir)
            val process = ffmpegProcess(command).redirectErrorStream(true)
                .redirectOutput(dir.resolve("ffmpeg.log").toFile()).start()
            try {
                val code = process.onExit().await().exitValue()
                if (code != 0) log.warn("multiview ffmpeg ended id={} exit={}", id, code)
            } finally {
                process.terminate()
            }
        }

        fun wire(): MultiviewSession = MultiviewSession(
            id,
            channels,
            audio,
            PlaybackDescriptor(
                "hls",
                "application/vnd.apple.mpegurl",
                "/media/multiview/$id/$epoch/$secret/$MULTIVIEW_PLAYLIST",
                epoch,
            ),
            expires.toString(),
        )
    }

    private companion object {
        /** How long a session lives past its last renewal or media request. */
        val LEASE: Duration = Duration.ofSeconds(60)

        /** How long the first playlist request waits for FFmpeg's first segments. */
        val START_WAIT: Duration = Duration.ofSeconds(20)
        const val POLL_MS = 250L
        const val RETRY_MS = 2_000L
        const val CLIENT = "Multiview"
        const val SECRET_BYTES = 16
        val NAME = Regex("index\\.m3u8|seg[0-9]{1,9}\\.ts")

        fun secret(): String = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))

        fun missing() =
            ApiFailure(HttpStatusCode.Gone, Wire.ErrorCode.SESSION_EXPIRED, "Session was released or expired")

        fun invalid(message: String): Nothing = throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", message)
    }
}
