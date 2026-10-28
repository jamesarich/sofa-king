// The shapes are MilkDrop's eight wave modes, drawn by eye; the numbers are their sizes and offsets.
@file:Suppress("MagicNumber")

package dev.jamesrich.sofaking.visualizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What a preset sets about its waveform this frame. */
internal class WaveStyle(
    val mode: Int,
    val x: Float,
    val y: Float,
    val scale: Float,
    val smoothing: Float,
    val color: Triple<Float, Float, Float>,
    val alpha: Float,
    val additive: Boolean,
    val dots: Boolean,
    val thick: Boolean,
)

/** Draws the waveform of a frame over a [Canvas]. */
internal object Waves {
    private const val SPECTRUM_SHOWN = 256

    fun draw(canvas: Canvas, audio: AudioFrame, style: WaveStyle) {
        if (style.alpha <= 0f) return
        val left = smooth(audio.left, style.smoothing)
        val right = smooth(audio.right, style.smoothing)
        val points = shape(style, left, right, audio.spectrum, canvas.height.toFloat() / canvas.width)
        val closed = style.mode == 0 || style.mode == 1
        val count = points.size / 2
        val x = FloatArray(count) { points[it * 2] * canvas.width }
        val y = FloatArray(count) { (1f - points[it * 2 + 1]) * canvas.height }
        val (r, g, b) = style.color
        if (style.dots) {
            for (i in 0 until count) canvas.plot(x[i].toInt(), y[i].toInt(), r, g, b, style.alpha, style.additive)
            return
        }
        for (i in 0 until count - 1) {
            canvas.line(x[i], y[i], x[i + 1], y[i + 1], style.color, style.alpha, style.additive, style.thick)
        }
        if (closed) {
            canvas.line(x[count - 1], y[count - 1], x[0], y[0], style.color, style.alpha, style.additive, style.thick)
        }
    }

    private fun smooth(samples: FloatArray, amount: Float): FloatArray {
        val k = amount.coerceIn(0f, 1f) * 0.5f
        if (k == 0f) return samples
        return FloatArray(samples.size) { i ->
            val before = samples[maxOf(i - 1, 0)]
            val after = samples[minOf(i + 1, samples.size - 1)]
            samples[i] * (1 - k) + (before + after) * 0.5f * k
        }
    }

    /** The points of the wave as x, y pairs in 0 to 1, y up. */
    private fun shape(s: WaveStyle, l: FloatArray, r: FloatArray, spectrum: FloatArray, aspect: Float): FloatArray {
        val n = l.size
        val out = FloatArray(n * 2)
        val k = s.scale
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            val angle = (2 * PI * i / n).toFloat()
            val x: Float
            val y: Float
            when (s.mode) {
                0 -> {
                    val radius = 0.3f + 0.25f * l[i] * k
                    x = s.x + radius * cos(angle) * aspect
                    y = s.y + radius * sin(angle)
                }

                1 -> {
                    val radius = 0.25f + 0.3f * (l[i] + r[(i + n / 2) % n]) * 0.5f * k
                    x = s.x + radius * cos(angle * 3) * aspect
                    y = s.y + radius * sin(angle * 2)
                }

                2 -> {
                    x = s.x + l[i] * k * 0.5f * aspect * 2
                    y = s.y + r[i] * k * 0.5f
                }

                3 -> {
                    x = t
                    y = 0.08f + sqrt(spectrum[(t * SPECTRUM_SHOWN).toInt().coerceAtMost(SPECTRUM_SHOWN - 1)]) * 0.8f * k
                }

                4 -> {
                    x = t
                    y = s.y + l[i] * k * 0.35f
                }

                5 -> {
                    x = s.x + (l[i] * COS_45 - r[i] * SIN_45) * k * 0.45f * aspect * 2
                    y = s.y + (l[i] * SIN_45 + r[i] * COS_45) * k * 0.45f
                }

                6 -> {
                    x = t * 0.9f + 0.05f - l[i] * k * 0.12f * aspect
                    y = t * 0.9f + 0.05f + l[i] * k * 0.12f
                }

                else -> {
                    val side = if (i % 2 == 0) l[i] else r[i]
                    x = t
                    y = s.y + (if (i % 2 == 0) 0.18f else -0.18f) + side * k * 0.2f
                }
            }
            out[i * 2] = x
            out[i * 2 + 1] = y
        }
        return out
    }

    private val COS_45 = cos(PI / 4).toFloat()
    private val SIN_45 = sin(PI / 4).toFloat()
}
