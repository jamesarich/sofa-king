// The post-processing constants are MilkDrop's own: the echo's blend, the solarize curve.
@file:Suppress("MagicNumber")

package dev.jamesrich.sofaking.visualizer

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A MilkDrop-style visualizer on the CPU. [render] takes what the music sounds like now and returns the picture as
 * ARGB pixels, [width] by [height]: the last picture warped and faded by the preset's equations, the waveform drawn
 * over it, then the finishing touches (echo, gamma, brighten, darken, solarize, invert) for the screen only.
 */
class Visualizer(val width: Int, val height: Int, preset: MilkPreset, private val seed: Int = 1) {
    private var canvas = Canvas(width, height)
    private var spare = Canvas(width, height)
    private val warp = Warp(width, height)
    private lateinit var equations: Equations
    private lateinit var custom: CustomObjects
    private var time = 0.0
    private var frames = 0L

    /** How many of the preset's equation programs could not be read, so it draws without them. */
    val unreadable: Int get() = equations.unreadable + custom.unreadable

    /** The picture of the latest [render], reused each frame. */
    val pixels = IntArray(width * height)

    var preset: MilkPreset = preset
        set(value) {
            field = value
            equations = Equations(value, seed)
            custom = CustomObjects(value)
        }

    init {
        this.preset = preset
    }

    /** Clears the picture, so the next preset does not start inside the last one's. */
    fun reset() {
        canvas.clear()
        spare.clear()
        time = 0.0
        frames = 0
        equations = Equations(preset, seed)
        custom = CustomObjects(preset)
    }

    fun render(audio: AudioFrame, dtSeconds: Double): IntArray {
        val dt = dtSeconds.coerceIn(MIN_DT, MAX_DT)
        time += dt
        val state = equations.runFrame(audio, time, frames, 1 / dt)
        frames++
        warp.buildMesh(equations, state, time)
        warp.apply(canvas, spare, state["decay"].toFloat().coerceIn(0f, 1f))
        canvas.swap(spare)
        if (!custom.isEmpty) custom.draw(canvas, Hearing(audio, time, frames, equations.scope))
        Waves.draw(canvas, audio, waveStyle(state))
        canvas.border(state["ob_size"].toFloat(), 0f, state.color("ob"), state["ob_a"].toFloat())
        canvas.border(
            state["ib_size"].toFloat(),
            state["ob_size"].toFloat(),
            state.color("ib"),
            state["ib_a"].toFloat(),
        )
        finish(state)
        return pixels
    }

    private fun waveStyle(state: FrameState): WaveStyle {
        var color = state.color("wave")
        if (state.flag("maximizewavecolor")) {
            val peak = maxOf(color.first, color.second, color.third)
            if (peak > 0f) color = Triple(color.first / peak, color.second / peak, color.third / peak)
        }
        return WaveStyle(
            mode = state["wave_mode"].toInt().coerceIn(0, 7),
            x = state["wave_x"].toFloat(),
            y = state["wave_y"].toFloat(),
            scale = state["wave_scale"].toFloat(),
            smoothing = state["wave_smoothing"].toFloat(),
            color = color,
            alpha = state["wave_alpha"].toFloat().coerceIn(0f, 1f),
            additive = state.flag("additivewave"),
            dots = state.flag("wavedots"),
            thick = state.flag("wavethick"),
        )
    }

    private fun finish(state: FrameState) {
        val gamma = state["gamma"].toFloat()
        val echoAlpha = state["echo_alpha"].toFloat().coerceIn(0f, 1f)
        val echoZoom = state["echo_zoom"].toFloat().coerceAtLeast(MIN_ECHO_ZOOM)
        val orient = state["echo_orient"].toInt() and 3
        val brighten = state.flag("brighten")
        val darken = state.flag("darken")
        val solarize = state.flag("solarize")
        val invert = state.flag("invert")
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                var r = canvas.red[i]
                var g = canvas.green[i]
                var b = canvas.blue[i]
                if (echoAlpha > 0f) {
                    val e = echoIndex(x, y, echoZoom, orient)
                    r += (canvas.red[e] - r) * echoAlpha
                    g += (canvas.green[e] - g) * echoAlpha
                    b += (canvas.blue[e] - b) * echoAlpha
                }
                r *= gamma
                g *= gamma
                b *= gamma
                if (brighten) {
                    r = sqrt(r)
                    g = sqrt(g)
                    b = sqrt(b)
                }
                if (darken) {
                    r *= r
                    g *= g
                    b *= b
                }
                if (solarize) {
                    r *= (1 - r) * 4
                    g *= (1 - g) * 4
                    b *= (1 - b) * 4
                }
                if (invert) {
                    r = 1 - r
                    g = 1 - g
                    b = 1 - b
                }
                pixels[i] = pack(r, g, b)
            }
        }
    }

    /** The pixel the echo copy shows at ([x], [y]): the picture zoomed about its center and flipped by [orient]. */
    private fun echoIndex(x: Int, y: Int, zoom: Float, orient: Int): Int {
        var u = x
        var v = y
        if (orient and 1 != 0) u = width - 1 - u
        if (orient and 2 != 0) v = height - 1 - v
        val ex = floor((u - width / 2f) / zoom + width / 2f).toInt().coerceIn(0, width - 1)
        val ey = floor((v - height / 2f) / zoom + height / 2f).toInt().coerceIn(0, height - 1)
        return ey * width + ex
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private companion object {
        const val MIN_DT = 1.0 / 240
        const val MAX_DT = 0.25
        const val MIN_ECHO_ZOOM = 0.1f
    }
}
