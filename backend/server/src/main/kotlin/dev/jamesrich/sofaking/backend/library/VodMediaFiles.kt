package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.sameSecret
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/** How often, and how many times, a request checks for the segment FFmpeg is writing: 20 seconds in all. */
private const val READY_CHECKS = 200
private const val READY_CHECK_MS = 100L

/** How far past the last segment written a request may be and still wait for the running FFmpeg. */
private const val LOOKAHEAD_SEGMENTS = 3

private val SEGMENT = Regex("seg([0-9]{1,6})\\.ts")
private val FMP4_SEGMENT = Regex("seg[0-9]{1,6}\\.m4s")

private const val PLAYLIST = "index.m3u8"
private const val PLAYLIST_TYPE = "application/vnd.apple.mpegurl"

/** The files behind VOD media URLs; each request renews its session's lease. */
class VodMediaFiles internal constructor(
    private val table: VodTable,
    private val runner: VodRunner,
    private val clock: Clock,
) {
    /** The file behind a media URL, waiting a little for FFmpeg to write it; null when the URL is stale or unknown. */
    suspend fun media(id: String, epoch: Int, secret: String, name: String): VodMedia? {
        val state = table.locked {
            val state = table.sessions[id]?.takeIf { it.epoch == epoch && sameSecret(it.secret, secret) } ?: return null
            state.expires = clock.instant().plus(state.lease)
            state
        }
        val dir = runner.folderOf(state)
        return when {
            state.mode == Wire.VodMode.DIRECT ->
                VodMedia(state.file, directType(state.media)).takeIf { name == "file.${extension(state.media)}" }

            state.whole -> whole(state, dir, name)

            name == PLAYLIST -> waitFor(state, epoch) {
                Files.isRegularFile(dir.resolve(name)) && Files.isRegularFile(dir.resolve(firstSegment(state)))
            }?.let { VodMedia(dir.resolve(name), PLAYLIST_TYPE) }

            SEGMENT.matches(name) -> waitFor(state, epoch) { Files.isRegularFile(dir.resolve(name)) }
                ?.let { VodMedia(dir.resolve(name), "video/mp2t") }

            FMP4_SEGMENT.matches(name) || name == FMP4_INIT -> waitFor(state, epoch) {
                Files.isRegularFile(dir.resolve(name))
            }?.let { VodMedia(dir.resolve(name), "video/mp4") }

            else -> null
        }
    }

    /**
     * A whole-playlist session's media: the playlist, written once, or a segment, converted from there when FFmpeg is
     * not already on its way to it.
     */
    private suspend fun whole(state: VodState, dir: Path, name: String): VodMedia? {
        if (name == PLAYLIST) return VodMedia(dir.resolve(name), PLAYLIST_TYPE)
        val segment = SEGMENT.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it < segmentCount(state.durationMs!!) }
            ?: return null
        val file = dir.resolve(name)
        if (withContext(Dispatchers.IO) { Files.isRegularFile(file) }) return VodMedia(file, "video/mp2t")
        val epoch = state.restarting.withLock {
            val jump = table.locked {
                if (table.sessions[state.id] !== state) return null
                val running = state.process?.isAlive == true
                val reached = withContext(Dispatchers.IO) { written(dir, state.runStart) }
                !running || segment < state.runStart || segment > reached + LOOKAHEAD_SEGMENTS
            }
            if (jump) {
                runner.stop(state, clearFiles = false)
                table.locked { state.runStart = segment }
                table.launchOwned(state)
            }
            state.epoch
        }
        return waitFor(state, epoch) { Files.isRegularFile(file) }?.let { VodMedia(file, "video/mp2t") }
    }

    /**
     * Waits until [ready], while [state]'s FFmpeg of [epoch] runs; null when FFmpeg stops before it is, or after
     * [READY_CHECKS] checks.
     */
    private suspend fun waitFor(state: VodState, epoch: Int, ready: () -> Boolean): Unit? {
        var checks = 0
        while (checks < READY_CHECKS && !withContext(Dispatchers.IO) { ready() }) {
            val running = table.locked {
                state.epoch == epoch && state.process?.isAlive == true && table.sessions[state.id] === state
            }
            if (!running) return Unit.takeIf { withContext(Dispatchers.IO) { ready() } }
            delay(READY_CHECK_MS)
            checks++
        }
        return Unit.takeIf { checks < READY_CHECKS }
    }

    private companion object {
        /** The last segment written in a row from [start], or `start - 1` before the first. */
        fun written(dir: Path, start: Int): Int {
            var last = start - 1
            while (Files.isRegularFile(dir.resolve("seg${last + 1}.ts"))) last++
            return last
        }

        fun firstSegment(state: VodState) = if (fragmentedMp4(state.mode, state.media)) "seg0.m4s" else "seg0.ts"
    }
}
