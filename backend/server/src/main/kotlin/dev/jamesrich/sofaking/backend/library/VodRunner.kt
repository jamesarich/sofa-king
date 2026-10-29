package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.io.terminate
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val log = LoggerFactory.getLogger(VodSessions::class.java)

/** Starts and stops the FFmpeg that writes each converted session's HLS stream into its folder under [directory]. */
internal class VodRunner(private val ffmpeg: String, private val directory: Path, var encoder: VideoEncoder) {
    fun folderOf(state: VodState): Path = directory.resolve(state.id)

    suspend fun launch(state: VodState) = withContext(Dispatchers.IO) {
        val dir = folderOf(state)
        // A whole playlist keeps the segments already converted, so a seek back to them plays at once.
        if (!state.whole || !Files.isDirectory(dir)) {
            clear(dir)
            Files.createDirectories(dir)
            if (state.whole) Files.writeString(dir.resolve("index.m3u8"), wholePlaylist(state.durationMs!!))
        }
        val command = if (state.whole) {
            vodCommand(
                ffmpeg, state.file, dir, state.mode, state.runStart * SEGMENT_MS, state.media, state.audioIndex,
                encoder = encoder, burn = state.burn, firstSegment = state.runStart,
            )
        } else {
            vodCommand(
                ffmpeg, state.file, dir, state.mode, state.startMs, state.media, state.audioIndex,
                follow = state.growing, encoder = encoder, burn = state.burn,
            )
        }
        state.process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("ffmpeg.log").toFile()).start()
        } catch (e: IOException) {
            log.warn("vod ffmpeg did not start class={}", e.javaClass.simpleName)
            throw ApiFailure(
                HttpStatusCode.ServiceUnavailable,
                "TRANSCODER_UNAVAILABLE",
                "The server couldn't start this title",
                true,
            )
        }
        log.info(
            "vod session started session={} mode={} startMs={}",
            state.id,
            state.mode,
            if (state.whole) state.runStart * SEGMENT_MS else state.startMs,
        )
    }

    suspend fun stop(state: VodState, clearFiles: Boolean = true) {
        val process = state.process
        state.process = null
        withContext(Dispatchers.IO) {
            process?.terminate()
            if (clearFiles) clear(folderOf(state))
        }
    }

    companion object {
        /**
         * Deletes [dir] and what it holds. A seek's restart and a release can stop one session at once, so a folder
         * the other stop deletes first is already done.
         */
        fun clear(dir: Path) {
            if (!Files.isDirectory(dir)) return
            try {
                Files.walk(dir).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
                }
            } catch (_: NoSuchFileException) {
                // Deleted by the other stop before the walk began.
            } catch (e: UncheckedIOException) {
                // Or while it ran.
                if (e.cause !is NoSuchFileException) throw e
            }
        }
    }
}
