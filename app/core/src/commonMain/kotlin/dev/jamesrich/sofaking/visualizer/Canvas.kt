package dev.jamesrich.sofaking.visualizer

import kotlin.math.abs
import kotlin.math.max

/** How many picture widths off the picture a line may start or end and still be drawn. */
private const val OFF_PICTURE = 8

/** The picture the feedback loop paints on: three float channels per pixel, 0 to 1, and the lines drawn over them. */
internal class Canvas(val width: Int, val height: Int) {
    var red = FloatArray(width * height)
    var green = FloatArray(width * height)
    var blue = FloatArray(width * height)

    /** Swaps this picture's channels with [other]'s, for the cheap hand-over between two frames. */
    fun swap(other: Canvas) {
        red = other.red.also { other.red = red }
        green = other.green.also { other.green = green }
        blue = other.blue.also { other.blue = blue }
    }

    fun clear() {
        red.fill(0f)
        green.fill(0f)
        blue.fill(0f)
    }

    /** Paints a pixel with [alpha] of the color, or adds that much of it when [additive]. */
    fun plot(x: Int, y: Int, r: Float, g: Float, b: Float, alpha: Float, additive: Boolean) {
        if (x !in 0 until width || y !in 0 until height) return
        val i = y * width + x
        if (additive) {
            red[i] = (red[i] + r * alpha).coerceAtMost(1f)
            green[i] = (green[i] + g * alpha).coerceAtMost(1f)
            blue[i] = (blue[i] + b * alpha).coerceAtMost(1f)
        } else {
            red[i] += (r - red[i]) * alpha
            green[i] += (g - green[i]) * alpha
            blue[i] += (b - blue[i]) * alpha
        }
    }

    /** A line from one point to another in pixels; [thick] paints a 2 by 2 block at each step. */
    fun line(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: Triple<Float, Float, Float>,
        alpha: Float,
        additive: Boolean,
        thick: Boolean,
    ) {
        // A preset can send a point to infinity; a line that far off the picture shows nothing and would take forever.
        val reach = max(width, height) * OFF_PICTURE
        if (listOf(x0, y0, x1, y1).any { !it.isFinite() || abs(it) > reach }) return
        val steps = max(abs(x1 - x0), abs(y1 - y0)).toInt().coerceAtLeast(1)
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val x = (x0 + (x1 - x0) * t).toInt()
            val y = (y0 + (y1 - y0) * t).toInt()
            plot(x, y, color.first, color.second, color.third, alpha, additive)
            if (thick) {
                plot(x + 1, y, color.first, color.second, color.third, alpha, additive)
                plot(x, y + 1, color.first, color.second, color.third, alpha, additive)
                plot(x + 1, y + 1, color.first, color.second, color.third, alpha, additive)
            }
        }
    }

    /** A frame [size] (a fraction of the picture) wide around the edge, or around an inner box when [inset]. */
    fun border(size: Float, inset: Float, color: Triple<Float, Float, Float>, alpha: Float) {
        if (alpha <= 0f || size <= 0f) return
        val edge = (inset * width).toInt()
        val thickness = (size * width).toInt().coerceAtLeast(1)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val toEdge = minOf(x, y, width - 1 - x, height - 1 - y)
                if (toEdge in
                    edge until edge + thickness
                ) {
                    plot(x, y, color.first, color.second, color.third, alpha, false)
                }
            }
        }
    }
}
