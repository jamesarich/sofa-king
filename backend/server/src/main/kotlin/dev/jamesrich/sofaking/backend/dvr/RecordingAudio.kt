package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.io.startOwned
import dev.jamesrich.sofaking.backend.io.terminate
import dev.jamesrich.sofaking.backend.playback.AUDIO_RATE
import dev.jamesrich.sofaking.backend.playback.LOUDNESS_WINDOW_MS
import dev.jamesrich.sofaking.backend.playback.SILENT_DB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** What a finished recording sounds like, as the break search reads it. */
interface RecordingSound {
    /** The level in dBFS of each [LOUDNESS_WINDOW_MS] of [file]'s audio; null when it has none or cannot be read. */
    suspend fun levels(file: Path): FloatArray?

    /** The Chromaprint points of [file]'s audio, one per hop (see `CHROMAPRINT_HOP`); null when it cannot be read. */
    suspend fun points(file: Path): IntArray?
}

/**
 * Reads a finished recording's first audio track with FFmpeg, at [AUDIO_RATE] Hz mono, without holding it: [levels]
 * keeps one number per [LOUDNESS_WINDOW_MS] of the audio as it streams by, and [points] has FFmpeg fingerprint it
 * with its `chromaprint` muxer, which keeps only the points. Each runs at a low priority where `nice` exists, and
 * is stopped after [timeout].
 */
class RecordingAudio(private val ffmpeg: String, private val timeout: Duration = 6.hours) : RecordingSound {
    override suspend fun levels(file: Path): FloatArray? = run(file, listOf("-f", "s16le")) { input ->
        val levels = Levels()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            levels.add(buffer, read)
        }
        levels.result()
    }

    override suspend fun points(file: Path): IntArray? =
        run(file, listOf("-f", "chromaprint", "-fp_format", "raw")) { input ->
            val words = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            IntArray(words.remaining()).also(words::get)
        }

    private suspend fun <T : Any> run(file: Path, format: List<String>, read: (java.io.InputStream) -> T): T? {
        val command = (if (Files.isExecutable(Path.of(NICE))) listOf(NICE, "-n", "10") else emptyList()) +
            listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-i", file.toString()) +
            listOf("-map", "0:a:0", "-ac", "1", "-ar", AUDIO_RATE.toString()) + format + "pipe:1"
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).startOwned()
        try {
            return coroutineScope {
                val watchdog = launch {
                    delay(timeout)
                    process.terminate()
                }
                val output = runInterruptible(Dispatchers.IO) { process.inputStream.use(read) }
                val exit = process.onExit().await().exitValue()
                watchdog.cancel()
                output.takeIf { exit == 0 }
            }
        } finally {
            process.terminate()
        }
    }

    /** Streams signed 16-bit little-endian samples into one level per [LOUDNESS_WINDOW_MS], on exact boundaries. */
    private class Levels {
        private var out = FloatArray(INITIAL)
        private var size = 0
        private var sum = 0.0
        private var inWindow = 0L
        private var window = 1L
        private var samples = 0L
        private var low = -1

        fun add(buffer: ByteArray, length: Int) {
            for (i in 0 until length) {
                if (low < 0) {
                    low = buffer[i].toInt() and BYTE_MASK
                    continue
                }
                val value = low or (buffer[i].toInt() shl BYTE_BITS)
                low = -1
                sum += value.toDouble() * value
                inWindow++
                samples++
                if (samples == window * AUDIO_RATE * LOUDNESS_WINDOW_MS / MS_PER_S) close()
            }
        }

        private fun close() {
            val rms = sqrt(sum / inWindow)
            if (size == out.size) out = out.copyOf(size + GROW)
            out[size++] = if (rms < 1.0) SILENT_DB else maxOf(SILENT_DB, (DB * log10(rms / FULL_SCALE)).toFloat())
            sum = 0.0
            inWindow = 0
            window++
        }

        fun result(): FloatArray = out.copyOf(size)
    }

    private companion object {
        const val NICE = "/usr/bin/nice"
        const val BUFFER = 64 * 1024
        const val INITIAL = 1024
        const val GROW = 16 * 1024
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xFF
        const val MS_PER_S = 1000L
        const val DB = 20
        const val FULL_SCALE = 32_768.0
    }
}
