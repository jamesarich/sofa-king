package dev.jamesrich.sofaking.visualizer

import dev.jamesrich.sofaking.visualizer.eel.EelException
import dev.jamesrich.sofaking.visualizer.eel.EelScope
import dev.jamesrich.sofaking.visualizer.eel.Expr
import kotlin.random.Random

private const val HALF = 0.5

/** What a preset's per-frame equations left in the variables the renderer reads. */
internal class FrameState(private val scope: EelScope) {
    operator fun get(name: String): Double = scope[name]

    fun color(prefix: String) = Triple(
        get("${prefix}_r").toFloat().coerceIn(0f, 1f),
        get("${prefix}_g").toFloat().coerceIn(0f, 1f),
        get("${prefix}_b").toFloat().coerceIn(0f, 1f),
    )

    fun flag(name: String) = get(name) >= HALF
}

/** A preset's equations compiled over one scope, ready to run once per frame and once per mesh vertex. */
internal class Equations(preset: MilkPreset, seed: Int) {
    val scope = EelScope(Random(seed))
    private val values = MilkPreset.Defaults + preset.values
    private val frame = compile(preset.frame)
    private val pixel = compile(preset.pixel)

    /** True when the preset has per-pixel equations, so each mesh vertex needs its own run. */
    val perVertex = preset.pixel.isNotBlank() && pixel != null

    private val x = scope.slot("x")
    private val y = scope.slot("y")
    private val rad = scope.slot("rad")
    private val ang = scope.slot("ang")
    private val moved = VERTEX_OUTPUTS.map { scope.slot(it) }

    init {
        // q1..q32 and the like keep what the init program leaves in them; the frame resets the rest.
        setBase()
        compile(preset.init)?.eval()
    }

    private fun compile(source: String): Expr? {
        if (source.isBlank()) return null
        return try {
            scope.compile(source)
        } catch (_: EelException) {
            // A line this evaluator cannot read: the preset draws without its equations, as the others do.
            null
        }
    }

    private fun setBase() {
        for ((name, value) in values) scope[name] = value
    }

    fun runFrame(audio: AudioFrame, time: Double, frameNumber: Long, fps: Double): FrameState {
        setBase()
        scope["time"] = time
        scope["frame"] = frameNumber.toDouble()
        scope["fps"] = fps
        scope["bass"] = audio.bass
        scope["mid"] = audio.mid
        scope["treb"] = audio.treb
        scope["bass_att"] = audio.bassAtt
        scope["mid_att"] = audio.midAtt
        scope["treb_att"] = audio.trebAtt
        frame?.eval()
        return FrameState(scope)
    }

    /**
     * The warp inputs at one mesh vertex, at ([px], [py]) in 0 to 1 and [radius]/[angle] from the center: the frame's
     * values, changed by the per-pixel equations. Fills [out] in [VERTEX_OUTPUTS] order.
     */
    fun runVertex(px: Double, py: Double, radius: Double, angle: Double, out: DoubleArray) {
        scope.values[x] = px
        scope.values[y] = py
        scope.values[rad] = radius
        scope.values[ang] = angle
        // The frame's own values stand in for the vertex's until the equations change them.
        if (pixel != null) {
            for (i in VERTEX_OUTPUTS.indices) scope.values[moved[i]] = frameValues[i]
            pixel.eval()
        }
        for (i in VERTEX_OUTPUTS.indices) out[i] = scope.values[moved[i]]
    }

    private val frameValues = DoubleArray(VERTEX_OUTPUTS.size)

    /** Remembers the frame's warp values, to start each vertex from them. */
    fun captureFrame() {
        for (i in VERTEX_OUTPUTS.indices) frameValues[i] = scope.values[moved[i]]
    }

    companion object {
        val VERTEX_OUTPUTS = listOf("zoom", "zoomexp", "rot", "warp", "cx", "cy", "dx", "dy", "sx", "sy")
    }
}
