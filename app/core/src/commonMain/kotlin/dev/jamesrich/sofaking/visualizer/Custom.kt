// The numbers are MilkDrop's: the most points a custom wave draws, how many q variables a preset has.
@file:Suppress("MagicNumber")

package dev.jamesrich.sofaking.visualizer

import dev.jamesrich.sofaking.visualizer.eel.EelException
import dev.jamesrich.sofaking.visualizer.eel.EelScope
import dev.jamesrich.sofaking.visualizer.eel.Expr
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val Q_COUNT = 32
private const val MAX_POINTS = 512

/** What a custom wave starts with, by variable name. */
internal val WAVE_DEFAULTS: Map<String, Double> = mapOf(
    "r" to 1.0,
    "g" to 1.0,
    "b" to 1.0,
    "a" to 1.0,
    "samples" to 512.0,
    "sep" to 0.0,
    "scaling" to 1.0,
    "smoothing" to 0.5,
    "spectrum" to 0.0,
    "usedots" to 0.0,
    "drawthick" to 0.0,
    "additive" to 0.0,
)

/** What a custom shape starts with, by variable name. */
internal val SHAPE_DEFAULTS: Map<String, Double> = mapOf(
    "sides" to 4.0,
    "x" to 0.5,
    "y" to 0.5,
    "rad" to 0.1,
    "ang" to 0.0,
    "r" to 1.0,
    "g" to 0.0,
    "b" to 0.0,
    "a" to 1.0,
    "r2" to 0.0,
    "g2" to 1.0,
    "b2" to 0.0,
    "a2" to 0.0,
    "border_r" to 1.0,
    "border_g" to 1.0,
    "border_b" to 1.0,
    "border_a" to 0.1,
    "additive" to 0.0,
    "thickoutline" to 0.0,
    "num_inst" to 1.0,
)

private val Q_NAMES = (1..Q_COUNT).map { "q$it" }

/** What a custom wave or shape reads from the frame it is drawn in. */
internal class Hearing(val audio: AudioFrame, val time: Double, val frame: Long, val main: EelScope)

/** Runs one custom object's programs in a scope of its own, with the preset's q variables copied in each frame. */
private class Program(def: CustomDef, defaults: Map<String, Double>) {
    val scope = EelScope()
    var unreadable = 0
        private set
    private val values = defaults + def.values
    private val frame = compile(def.frame)
    val point = compile(def.point)

    init {
        for ((name, value) in values) scope[name] = value
        compile(def.init)?.eval()
    }

    private fun compile(source: String): Expr? = if (source.isBlank()) {
        null
    } else {
        try {
            scope.compile(source)
        } catch (_: EelException) {
            // A line this evaluator cannot read: the object draws without its equations.
            unreadable++
            null
        }
    }

    /** Starts a frame, or a shape's instance: the base values again, the q variables, the per-frame program. */
    fun startFrame(hearing: Hearing, instance: Int = 0, instances: Int = 1) {
        for ((name, value) in values) scope[name] = value
        scope["instance"] = instance.toDouble()
        scope["num_inst"] = instances.toDouble()
        for (name in Q_NAMES) scope[name] = hearing.main[name]
        scope["time"] = hearing.time
        scope["frame"] = hearing.frame.toDouble()
        scope["bass"] = hearing.audio.bass
        scope["mid"] = hearing.audio.mid
        scope["treb"] = hearing.audio.treb
        scope["bass_att"] = hearing.audio.bassAtt
        scope["mid_att"] = hearing.audio.midAtt
        scope["treb_att"] = hearing.audio.trebAtt
        frame?.eval()
    }
}

/** The custom waves and shapes of a preset, drawn over the picture each frame. */
internal class CustomObjects(preset: MilkPreset) {
    private val waves = preset.waves.map { CustomWave(it) }
    private val shapes = preset.shapes.map { CustomShape(it) }

    val isEmpty get() = waves.isEmpty() && shapes.isEmpty()

    /** How many of the objects' programs could not be read. */
    val unreadable get() = waves.sumOf { it.unreadable } + shapes.sumOf { it.unreadable }

    fun draw(canvas: Canvas, hearing: Hearing) {
        for (shape in shapes) shape.draw(canvas, hearing)
        for (wave in waves) wave.draw(canvas, hearing)
    }
}

private class CustomWave(def: CustomDef) {
    val unreadable get() = program.unreadable

    private val program = Program(def, WAVE_DEFAULTS)

    fun draw(canvas: Canvas, hearing: Hearing) {
        program.startFrame(hearing)
        val scope = program.scope
        val count = scope["samples"].toInt().coerceIn(2, MAX_POINTS)
        val spectrum = scope["spectrum"] >= 0.5
        val scaling = scope["scaling"]
        val left = if (spectrum) hearing.audio.spectrum else hearing.audio.left
        val right = if (spectrum) hearing.audio.spectrum else hearing.audio.right
        val additive = scope["additive"] >= 0.5
        val thick = scope["drawthick"] >= 0.5
        val dots = scope["usedots"] >= 0.5
        var previous: FloatArray? = null
        for (i in 0 until count) {
            val sample = i.toDouble() / (count - 1)
            scope["sample"] = sample
            scope["value1"] = value(left, sample, spectrum) * scaling
            scope["value2"] = value(right, sample, spectrum) * scaling
            scope["x"] = 0.5
            scope["y"] = 0.5
            program.point?.eval()
            val here = floatArrayOf(
                (scope["x"] * canvas.width).toFloat(),
                ((1 - scope["y"]) * canvas.height).toFloat(),
            )
            val color = Triple(scope["r"].toFloat(), scope["g"].toFloat(), scope["b"].toFloat())
            val alpha = scope["a"].toFloat().coerceIn(0f, 1f)
            if (dots) {
                canvas.plot(here[0].toInt(), here[1].toInt(), color.first, color.second, color.third, alpha, additive)
            } else {
                previous?.let { canvas.line(it[0], it[1], here[0], here[1], color, alpha, additive, thick) }
            }
            previous = here
        }
    }

    private fun value(samples: FloatArray, at: Double, spectrum: Boolean): Double {
        val size = if (spectrum) samples.size / 2 else samples.size
        val x = at * (size - 1)
        val i = x.toInt()
        val f = x - i
        val a = samples[i.coerceIn(0, size - 1)]
        val b = samples[(i + 1).coerceIn(0, size - 1)]
        return a + (b - a) * f
    }
}

private class CustomShape(def: CustomDef) {
    val unreadable get() = program.unreadable

    private val program = Program(def, SHAPE_DEFAULTS)
    private val instances = def.values["num_inst"]?.toInt()?.coerceIn(1, MAX_INSTANCES) ?: 1

    fun draw(canvas: Canvas, hearing: Hearing) {
        for (instance in 0 until instances) {
            program.startFrame(hearing, instance, instances)
            polygon(canvas)
        }
    }

    private fun polygon(canvas: Canvas) {
        val s = program.scope
        val sides = s["sides"].toInt().coerceIn(3, MAX_SIDES)
        val cx = s["x"] * canvas.width
        val cy = (1 - s["y"]) * canvas.height
        // The radius is a share of the picture's height, so a shape stays round.
        val radius = s["rad"] * canvas.height
        val angle = s["ang"]
        val additive = s["additive"] >= 0.5
        val center = floatArrayOf(s["r"].toFloat(), s["g"].toFloat(), s["b"].toFloat(), s["a"].toFloat())
        val edge = floatArrayOf(s["r2"].toFloat(), s["g2"].toFloat(), s["b2"].toFloat(), s["a2"].toFloat())
        val xs = FloatArray(sides) { (cx + radius * cos(angle + 2 * PI * it / sides)).toFloat() }
        val ys = FloatArray(sides) { (cy - radius * sin(angle + 2 * PI * it / sides)).toFloat() }
        for (k in 0 until sides) {
            val next = (k + 1) % sides
            triangle(canvas, cx.toFloat(), cy.toFloat(), xs[k], ys[k], xs[next], ys[next], center, edge, additive)
        }
        val border = Triple(s["border_r"].toFloat(), s["border_g"].toFloat(), s["border_b"].toFloat())
        val borderAlpha = s["border_a"].toFloat().coerceIn(0f, 1f)
        val thick = s["thickoutline"] >= 0.5
        if (borderAlpha > 0f) {
            for (k in 0 until sides) {
                val next = (k + 1) % sides
                canvas.line(xs[k], ys[k], xs[next], ys[next], border, borderAlpha, false, thick)
            }
        }
    }

    /** Fills the triangle with the center's color at its first corner fading to the edge's color at the others. */
    @Suppress("LongParameterList")
    private fun triangle(
        canvas: Canvas,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        center: FloatArray,
        edge: FloatArray,
        additive: Boolean,
    ) {
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (area == 0f) return
        val minX = max(0, min(x0, min(x1, x2)).toInt())
        val maxX = min(canvas.width - 1, max(x0, max(x1, x2)).toInt() + 1)
        val minY = max(0, min(y0, min(y1, y2)).toInt())
        val maxY = min(canvas.height - 1, max(y0, max(y1, y2)).toInt() + 1)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val px = x + 0.5f
                val py = y + 0.5f
                val w1 = ((px - x0) * (y2 - y0) - (x2 - x0) * (py - y0)) / area
                val w2 = ((x1 - x0) * (py - y0) - (px - x0) * (y1 - y0)) / area
                val w0 = 1 - w1 - w2
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val t = 1 - w0
                canvas.plot(
                    x,
                    y,
                    center[0] + (edge[0] - center[0]) * t,
                    center[1] + (edge[1] - center[1]) * t,
                    center[2] + (edge[2] - center[2]) * t,
                    (center[3] + (edge[3] - center[3]) * t).coerceIn(0f, 1f),
                    additive,
                )
            }
        }
    }

    private companion object {
        const val MAX_SIDES = 100
        const val MAX_INSTANCES = 1024
    }
}
