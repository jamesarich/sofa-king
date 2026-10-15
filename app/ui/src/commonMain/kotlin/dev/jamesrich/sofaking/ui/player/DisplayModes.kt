package dev.jamesrich.sofaking.ui.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** One mode the display offers, as the platform lists them. */
data class DisplayMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * The mode to switch the display to for video at [frameRate], or null to leave it: the current mode already
 * fits (its refresh rate is a whole multiple of the frame rate), the frame rate is unknown, or no mode of the
 * current size fits. Of the fitting modes the highest refresh rate wins, since it also suits the rest of the
 * lineup and costs no further switch.
 */
fun matchingDisplayMode(modes: List<DisplayMode>, currentId: Int, frameRate: Float?): DisplayMode? {
    if (frameRate == null || frameRate <= 0f) return null
    val current = modes.firstOrNull { it.id == currentId } ?: return null
    if (fits(current.refreshRate, frameRate)) return null
    return modes
        .filter { it.width == current.width && it.height == current.height && fits(it.refreshRate, frameRate) }
        .maxByOrNull { it.refreshRate }
}

/** 59.94 Hz fits 29.97 fps; 60 Hz does not (the 0.06 Hz difference is the judder the switch is for). */
internal fun fits(refreshRate: Float, frameRate: Float): Boolean {
    val multiple = (refreshRate / frameRate).roundToInt()
    return multiple >= 1 && abs(refreshRate - multiple * frameRate) <= TOLERANCE_HZ
}

private const val TOLERANCE_HZ = 0.05f
