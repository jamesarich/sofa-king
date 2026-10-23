package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.io.startOwned
import dev.jamesrich.sofaking.backend.io.terminate
import java.io.IOException
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How loud a transport stream segment's first audio track is, one level in dBFS per [LOUDNESS_WINDOW_MS], read with
 * FFmpeg: the segment goes in on stdin and 8 kHz mono PCM comes out. The learned-break rules read it (see
 * [AdClock.heard]). At most [parallelism] segments are read at once, server-wide.
 */
class SegmentLoudness(private val ffmpeg: String, parallelism: Int = 2, private val timeout: Duration = 10.seconds) {
    private val permits = Semaphore(parallelism)

    /** The levels of [segment], or null when it has no audio, FFmpeg cannot decode it, or it ran past the timeout. */
    suspend fun levels(segment: ByteArray): FloatArray? = permits.withPermit {
        val process = ProcessBuilder(
            ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-f", "mpegts", "-i", "pipe:0",
            "-map", "0:a:0", "-ac", "1", "-ar", SAMPLE_RATE.toString(), "-f", "s16le", "pipe:1",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).startOwned()
        try {
            coroutineScope {
                val watchdog = launch { delay(timeout); process.terminate() }
                // Written beside the read, since FFmpeg's output fills the pipe before it has read all of its input.
                launch(Dispatchers.IO) {
                    try { process.outputStream.use { it.write(segment) } } catch (_: IOException) { /* FFmpeg stopped reading: no audio */ }
                }
                val pcm = runInterruptible(Dispatchers.IO) { process.inputStream.readBytes() }
                val exit = process.onExit().await().exitValue()
                watchdog.cancel()
                if (exit == 0 && pcm.isNotEmpty()) loudness(pcm) else null
            }
        } finally {
            process.terminate()
        }
    }
}

/** The sample rate the levels are read at; speech and music fall well inside it. */
private const val SAMPLE_RATE = 8_000

/** How much audio each level covers. */
internal const val LOUDNESS_WINDOW_MS = 100L

/** The level of a window of digital silence, which has no logarithm of its own. */
internal const val SILENT_DB = -120f

/** The RMS level in dBFS of each whole [LOUDNESS_WINDOW_MS] of [pcm], signed 16-bit little-endian mono at [sampleRate]. */
internal fun loudness(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): FloatArray {
    val window = (sampleRate * LOUDNESS_WINDOW_MS / 1000).toInt()
    return FloatArray(pcm.size / 2 / window) { index ->
        var sum = 0.0
        for (sample in index * window until (index + 1) * window) {
            val value = (pcm[sample * 2].toInt() and 0xFF) or (pcm[sample * 2 + 1].toInt() shl 8)
            sum += value.toDouble() * value
        }
        val rms = sqrt(sum / window)
        if (rms < 1.0) SILENT_DB else maxOf(SILENT_DB, (20 * log10(rms / 32_768.0)).toFloat())
    }
}
