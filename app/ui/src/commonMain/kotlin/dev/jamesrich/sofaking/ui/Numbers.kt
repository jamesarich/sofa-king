package dev.jamesrich.sofaking.ui

import kotlin.math.abs
import kotlin.math.round

// Numbers as the screens write them, without a platform number formatter; the app's strings are US English.

/** A channel number without a whole number's ".0": 7.0 reads as 7, 5.1 as 5.1. */
internal fun Double.formatNumber(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

/** [value] to one decimal place, 59.94 as 59.9; [trimZero] drops a whole result's ".0", so 3.0 reads as 3. */
internal fun oneDecimal(value: Double, trimZero: Boolean = false): String {
    val tenths = round(value * 10).toLong()
    val sign = if (tenths < 0) "-" else ""
    val whole = abs(tenths) / 10
    val tenth = abs(tenths) % 10
    return if (trimZero && tenth == 0L) "$sign$whole" else "$sign$whole.$tenth"
}

/** A size in the largest unit that keeps it at 1 or more, with one decimal under 10: "740 MB", "2.4 GB". */
internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= BYTES_STEP && unit < units.lastIndex) {
        value /= BYTES_STEP
        unit++
    }
    val text = if (value < 10 && unit > 0) oneDecimal(value, trimZero = true) else round(value).toLong().toString()
    return "$text ${units[unit]}"
}

/** [ms] in whole seconds rounded up, so a countdown reads 1 until it is over. */
internal fun secondsUp(ms: Long): Int = ((ms + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt()

private const val MS_PER_SECOND = 1_000L

/** The decimal step between byte units, as drives and networks count them. */
private const val BYTES_STEP = 1000
