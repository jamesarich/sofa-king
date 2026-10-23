package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.io.startOwned
import dev.jamesrich.sofaking.backend.io.terminate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads segments' audio with FFmpeg for the learned breaks (see [AudioFollower]): [pcm] decodes a segment's first audio
 * track to [AUDIO_RATE] Hz mono, and [points] fingerprints decoded audio with FFmpeg's `chromaprint` muxer. At most
 * [parallelism] FFmpeg runs at once, server-wide.
 */
class SegmentAudio(private val ffmpeg: String, parallelism: Int = 2, private val timeout: Duration = 10.seconds) {
    private val permits = Semaphore(parallelism)

    /** [segment]'s audio as signed 16-bit little-endian samples, or null when it has none or FFmpeg cannot read it. */
    suspend fun pcm(segment: ByteArray): ByteArray? =
        run(segment, "-i", "pipe:0", "-map", "0:a:0", "-ac", "1", "-ar", AUDIO_RATE.toString(), "-f", "s16le", "pipe:1")

    /**
     * The Chromaprint points of [pcm], audio as [pcm] gives it: one for each [CHROMAPRINT_HOP] samples from its start
     * that has [CHROMAPRINT_CONTEXT] samples from there on; null when FFmpeg fails.
     */
    suspend fun points(pcm: ByteArray): IntArray? {
        val raw =
            run(
                pcm, "-f", "s16le", "-ar", AUDIO_RATE.toString(), "-ac", "1", "-i", "pipe:0", "-f", "chromaprint",
                "-fp_format", "raw", "pipe:1",
            )
                ?: return null
        val words = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(words.remaining()).also(words::get)
    }

    private suspend fun run(input: ByteArray, vararg args: String): ByteArray? = permits.withPermit {
        val process = ProcessBuilder(listOf(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error") + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD).startOwned()
        try {
            coroutineScope {
                val watchdog = launch {
                    delay(timeout)
                    process.terminate()
                }
                // Written beside the read, since FFmpeg's output fills the pipe before it has read all of its input.
                launch(Dispatchers.IO) {
                    try {
                        process.outputStream.use { it.write(input) }
                    } catch (
                        _: IOException,
                    ) { /* FFmpeg stopped reading */ }
                }
                val output = runInterruptible(Dispatchers.IO) { process.inputStream.readBytes() }
                val exit = process.onExit().await().exitValue()
                watchdog.cancel()
                output.takeIf { exit == 0 && it.isNotEmpty() }
            }
        } finally {
            process.terminate()
        }
    }
}

/** The rate audio is read at: Chromaprint's own, so its frames fall on whole samples. */
internal const val AUDIO_RATE = 11_025

/** Chromaprint's step between points, in samples at [AUDIO_RATE]. */
internal const val CHROMAPRINT_HOP = 1_365

/** The audio a point needs from its start: its 4096-sample frame and the 19 frames after it its filters read. */
internal const val CHROMAPRINT_CONTEXT = 4_096 + 19 * CHROMAPRINT_HOP

/** How much audio each level covers. */
internal const val LOUDNESS_WINDOW_MS = 100L

/** The level of a window of digital silence, which has no logarithm of its own. */
internal const val SILENT_DB = -120f

/**
 * The RMS level in dBFS of each whole [LOUDNESS_WINDOW_MS] of [pcm], signed 16-bit little-endian mono at [sampleRate].
 */
internal fun loudness(pcm: ByteArray, sampleRate: Int = AUDIO_RATE): FloatArray {
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
