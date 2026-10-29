package dev.jamesrich.sofaking.data

import kotlin.math.abs

/** How far one press of Earlier or Later moves the subtitles, and how far from the file's own timing they may go. */
const val SUBTITLE_OFFSET_STEP_MS = 250L
const val SUBTITLE_OFFSET_MAX_MS = 10_000L

/** [offsetMs] moved [steps] presses later (earlier when negative), within [SUBTITLE_OFFSET_MAX_MS] either way. */
fun stepSubtitleOffset(offsetMs: Long, steps: Int): Long =
    (offsetMs + steps * SUBTITLE_OFFSET_STEP_MS).coerceIn(-SUBTITLE_OFFSET_MAX_MS, SUBTITLE_OFFSET_MAX_MS)

/** An offset as the player shows it: "+0.25 s" for later, "−1.5 s" for earlier, "0 s" for the file's own timing. */
fun subtitleOffsetText(offsetMs: Long): String {
    if (offsetMs == 0L) return "0 s"
    val ms = abs(offsetMs)
    val fraction = (ms % 1000).toString().padStart(3, '0').trimEnd('0')
    val seconds = if (fraction.isEmpty()) "${ms / 1000}" else "${ms / 1000}.$fraction"
    return "${if (offsetMs > 0) "+" else "−"}$seconds s"
}
