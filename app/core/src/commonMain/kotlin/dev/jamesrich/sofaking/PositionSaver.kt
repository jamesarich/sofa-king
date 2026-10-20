package dev.jamesrich.sofaking

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * When a playing item's position is worth saving on the server: every [interval] per item, counted from its first
 * report, since a few seconds in is not worth a write. Read and written on the main thread.
 */
internal class PositionSaver(private val timeSource: TimeSource, private val interval: Duration) {
    private val saved = HashMap<String, TimeMark>()

    /** True when the position [id] reports now is due a save; the first report only starts the interval. */
    fun due(id: String): Boolean {
        val last = saved[id]
        if (last != null && last.elapsedNow() < interval) return false
        saved[id] = timeSource.markNow()
        return last != null
    }

    /** [id] starts or stops playing, so its next report starts the interval again. */
    fun reset(id: String) {
        saved.remove(id)
    }
}
