// The constants are the analysis's own: window sizes, band edges and smoothing rates.
@file:Suppress("MagicNumber")

package dev.jamesrich.sofaking.visualizer

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** How many samples of the waveform a frame holds, as MilkDrop's presets expect. */
const val WAVE_SAMPLES = 576

/** How many bins of the spectrum a frame holds. */
const val SPECTRUM_BINS = 512

/** The rate the analysis assumes until the player says what it plays at. */
const val DEFAULT_SAMPLE_RATE = 44_100

private const val FFT_SIZE = 1024
private const val FRAMES_PER_SECOND = 30.0

/**
 * What a preset hears for one frame: the waveform of each channel (-1 to 1), the spectrum (0 to 1), and the bass, mid
 * and treble levels as MilkDrop's equations read them: 1 is the song's own average, 1.5 a hit; the `Att` ones follow
 * more slowly.
 */
class AudioFrame(
    val left: FloatArray,
    val right: FloatArray,
    val spectrum: FloatArray,
    val bass: Double,
    val mid: Double,
    val treb: Double,
    val bassAtt: Double,
    val midAtt: Double,
    val trebAtt: Double,
) {
    companion object {
        /** A quiet room: a flat waveform and levels at their averages. */
        val Silent = AudioFrame(
            FloatArray(WAVE_SAMPLES),
            FloatArray(WAVE_SAMPLES),
            FloatArray(SPECTRUM_BINS),
            1.0,
            1.0,
            1.0,
            1.0,
            1.0,
            1.0,
        )
    }
}

/**
 * Turns the samples a player plays into [AudioFrame]s. [push] takes interleaved PCM from whichever thread decodes it;
 * [frame] reads the latest from the thread that draws. The two share ring buffers without a lock: a frame that reads
 * as a push lands is a little off, which nobody sees.
 */
class AudioAnalyzer(@Volatile var sampleRate: Int = DEFAULT_SAMPLE_RATE) {
    private val left = FloatArray(FFT_SIZE)
    private val right = FloatArray(FFT_SIZE)
    private var written = 0L

    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / FFT_SIZE)).toFloat() }
    private val fft = Fft(FFT_SIZE)
    private val real = DoubleArray(FFT_SIZE)
    private val imaginary = DoubleArray(FFT_SIZE)

    private val immediate = DoubleArray(BANDS)
    private val fast = DoubleArray(BANDS)
    private val average = DoubleArray(BANDS)

    /** Takes [count] frames of [channels]-channel interleaved samples from [samples]. */
    fun push(samples: FloatArray, count: Int, channels: Int) {
        var at = written
        for (i in 0 until count) {
            val l = samples[i * channels]
            val r = if (channels > 1) samples[i * channels + 1] else l
            val slot = (at % FFT_SIZE).toInt()
            left[slot] = l
            right[slot] = r
            at++
        }
        written = at
    }

    /**
     * Called with true while a visualizer draws from this analyzer and false when it stops, for a source that costs
     * something to tap (a second player decoding the same song) and runs only while it is heard.
     */
    var onWanted: ((Boolean) -> Unit)? = null

    /** Says whether a visualizer is drawing from this analyzer now. */
    fun want(wanted: Boolean) {
        onWanted?.invoke(wanted)
    }

    /** The latest frame; [dtSeconds] since the last one sets how fast the levels follow the music. */
    fun frame(dtSeconds: Double): AudioFrame {
        val end = written
        val l = FloatArray(WAVE_SAMPLES)
        val r = FloatArray(WAVE_SAMPLES)
        for (i in 0 until WAVE_SAMPLES) {
            val slot = ((end - WAVE_SAMPLES + i + FFT_SIZE) % FFT_SIZE).toInt()
            l[i] = left[slot]
            r[i] = right[slot]
        }
        val spectrum = spectrum(end)
        levels(spectrum, dtSeconds)
        return AudioFrame(
            l,
            r,
            spectrum,
            ratio(immediate[0], average[0]),
            ratio(immediate[1], average[1]),
            ratio(immediate[2], average[2]),
            ratio(fast[0], average[0]),
            ratio(fast[1], average[1]),
            ratio(fast[2], average[2]),
        )
    }

    private fun spectrum(end: Long): FloatArray {
        for (i in 0 until FFT_SIZE) {
            val slot = ((end - FFT_SIZE + i + FFT_SIZE) % FFT_SIZE).toInt()
            real[i] = (left[slot] + right[slot]) * 0.5 * window[i]
            imaginary[i] = 0.0
        }
        fft.transform(real, imaginary)
        // A full-scale sine gives 1 in its bin.
        return FloatArray(SPECTRUM_BINS) {
            (sqrt(real[it] * real[it] + imaginary[it] * imaginary[it]) * 4 / FFT_SIZE).toFloat().coerceAtMost(1f)
        }
    }

    private fun levels(spectrum: FloatArray, dt: Double) {
        val binHz = sampleRate.toDouble() / FFT_SIZE
        for (band in 0 until BANDS) {
            val from = (EDGES_HZ[band] / binHz).toInt().coerceIn(1, SPECTRUM_BINS - 1)
            val to = (EDGES_HZ[band + 1] / binHz).toInt().coerceIn(from + 1, SPECTRUM_BINS)
            var sum = 0.0
            for (bin in from until to) sum += spectrum[bin] * spectrum[bin]
            immediate[band] = sqrt(sum / (to - from))
            val frames = dt * FRAMES_PER_SECOND
            val rate = (if (immediate[band] > fast[band]) FAST_ATTACK else FAST_RELEASE).pow(frames)
            fast[band] = fast[band] * rate + immediate[band] * (1 - rate)
            average[band] = average[band] * AVERAGE_RATE.pow(frames) + immediate[band] * (1 - AVERAGE_RATE.pow(frames))
        }
    }

    /** [level] against the song's long average; 1 while the room is silent. */
    private fun ratio(level: Double, average: Double) = if (average < SILENCE) 1.0 else level / average

    private companion object {
        const val BANDS = 3
        val EDGES_HZ = doubleArrayOf(20.0, 250.0, 2000.0, 12000.0)
        const val FAST_ATTACK = 0.2
        const val FAST_RELEASE = 0.5
        const val AVERAGE_RATE = 0.992
        const val SILENCE = 1e-5
    }
}

/** A radix-2 FFT of a fixed [size], a power of two, in place. */
internal class Fft(private val size: Int) {
    private val cosines = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sines = DoubleArray(size / 2) { -sin(2 * PI * it / size) }

    fun transform(real: DoubleArray, imaginary: DoubleArray) {
        reorder(real, imaginary)
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            for (start in 0 until size step half * 2) {
                for (k in 0 until half) {
                    val wr = cosines[k * step]
                    val wi = sines[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = real[b] * wr - imaginary[b] * wi
                    val ti = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - tr
                    imaginary[b] = imaginary[a] - ti
                    real[a] += tr
                    imaginary[a] += ti
                }
            }
            half *= 2
        }
    }

    private fun reorder(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imaginary[i] = imaginary[j].also { imaginary[j] = imaginary[i] }
            }
        }
    }
}
