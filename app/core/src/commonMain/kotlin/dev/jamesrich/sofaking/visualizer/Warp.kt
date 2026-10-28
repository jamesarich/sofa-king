// MilkDrop's warp is built from fixed constants: the mesh size, the wobble's speed and strength.
@file:Suppress("MagicNumber")

package dev.jamesrich.sofaking.visualizer

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * The feedback step: each pixel of the new picture is the old picture looked up at a moved position, then faded. The
 * positions are worked out at the points of a coarse mesh, by the preset's per-pixel equations, and spread across the
 * pixels between them, as MilkDrop does on the GPU.
 */
internal class Warp(private val width: Int, private val height: Int) {
    private val meshU = FloatArray(VERTICES)
    private val meshV = FloatArray(VERTICES)
    private val inputs = DoubleArray(Equations.VERTEX_OUTPUTS.size)

    /** Looks the mesh up from the equations' state at [time]. */
    fun buildMesh(equations: Equations, state: FrameState, time: Double) {
        equations.captureFrame()
        val aspect = width.toDouble() / height
        val warpScale = state["warp"]
        for (j in 0..MESH_H) {
            for (i in 0..MESH_W) {
                val x = i.toDouble() / MESH_W
                val y = j.toDouble() / MESH_H
                val dx = (x - 0.5) * aspect
                val dy = y - 0.5
                val radius = hypot(dx, dy) / hypot(aspect * 0.5, 0.5)
                val angle = atan2(dy, dx)
                equations.runVertex(x, y, radius, angle, inputs)
                val at = j * (MESH_W + 1) + i
                source(x, y, radius, aspect, time, warpScale, at)
            }
        }
    }

    private fun source(x: Double, y: Double, radius: Double, aspect: Double, time: Double, warpScale: Double, at: Int) {
        val zoom = inputs[ZOOM]
        val zoomExp = inputs[ZOOM_EXP]
        val rot = inputs[ROT]
        val warp = inputs[WARP]
        val cx = inputs[CX]
        val cy = inputs[CY]
        var u = x - cx
        var v = y - cy
        // Above 1 the picture zooms in, so the lookup moves toward the center; the exponent bends that by radius.
        val factor = zoom.pow(zoomExp.pow(radius * 2 - 1)).takeIf { it > MIN_ZOOM } ?: MIN_ZOOM
        u /= factor * nonZero(inputs[SX])
        v /= factor * nonZero(inputs[SY])
        val ax = u * aspect
        val cosine = cos(rot)
        val sine = sin(rot)
        u = (ax * cosine - v * sine) / aspect
        v = ax * sine + v * cosine
        val wobble = warp * warpScale * WOBBLE
        u += wobble * sin(time * 0.333 + 10 * x + 3 * y)
        v += wobble * sin(time * 0.375 - 9 * y + 2 * x)
        meshU[at] = (u + cx - inputs[DX]).toFloat().let { if (it.isNaN()) 0.5f else it }
        meshV[at] = (v + cy - inputs[DY]).toFloat().let { if (it.isNaN()) 0.5f else it }
    }

    private fun nonZero(x: Double) = if (x > -MIN_ZOOM && x < MIN_ZOOM) MIN_ZOOM else x

    /** Writes [from] looked up through the mesh, times [decay], into [to]. */
    fun apply(from: Canvas, to: Canvas, decay: Float) {
        for (py in 0 until height) {
            val gy = (py + 0.5f) / height * MESH_H
            val j = floor(gy).toInt().coerceAtMost(MESH_H - 1)
            val fy = gy - j
            for (px in 0 until width) {
                val gx = (px + 0.5f) / width * MESH_W
                val i = floor(gx).toInt().coerceAtMost(MESH_W - 1)
                val fx = gx - i
                val a = j * (MESH_W + 1) + i
                val b = a + MESH_W + 1
                val u = lerp(lerp(meshU[a], meshU[a + 1], fx), lerp(meshU[b], meshU[b + 1], fx), fy)
                val v = lerp(lerp(meshV[a], meshV[a + 1], fx), lerp(meshV[b], meshV[b + 1], fx), fy)
                sample(from, to, u * width - 0.5f, v * height - 0.5f, py * width + px, decay)
            }
        }
    }

    private fun sample(from: Canvas, to: Canvas, sx: Float, sy: Float, into: Int, decay: Float) {
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val fx = sx - x0
        val fy = sy - y0
        val xa = x0.coerceIn(0, width - 1)
        val xb = (x0 + 1).coerceIn(0, width - 1)
        val ya = y0.coerceIn(0, height - 1) * width
        val yb = (y0 + 1).coerceIn(0, height - 1) * width
        to.red[into] = mix(from.red, xa, xb, ya, yb, fx, fy) * decay
        to.green[into] = mix(from.green, xa, xb, ya, yb, fx, fy) * decay
        to.blue[into] = mix(from.blue, xa, xb, ya, yb, fx, fy) * decay
    }

    private fun mix(c: FloatArray, xa: Int, xb: Int, ya: Int, yb: Int, fx: Float, fy: Float): Float =
        lerp(lerp(c[ya + xa], c[ya + xb], fx), lerp(c[yb + xa], c[yb + xb], fx), fy)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val MESH_W = 32
        const val MESH_H = 24
        const val VERTICES = (MESH_W + 1) * (MESH_H + 1)
        const val MIN_ZOOM = 0.001
        const val WOBBLE = 0.0035
        const val ZOOM = 0
        const val ZOOM_EXP = 1
        const val ROT = 2
        const val WARP = 3
        const val CX = 4
        const val CY = 5
        const val DX = 6
        const val DY = 7
        const val SX = 8
        const val SY = 9
    }
}
