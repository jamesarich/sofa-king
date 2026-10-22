package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.contract.AdRun
import dev.jamesrich.sofaking.data.AdBreakNow

/**
 * Whether the segment playing at [positionMs] is an ad, from the server's [AdRun] marks among a live media playlist's
 * [tags]. [mediaSequence] numbers the first of the playlist's segments, which start at [startsMs] and last
 * [durationsMs], on the same clock as [positionMs] (0 is the first segment's start, as Media3 counts a live window).
 * The break ends where the next show segment starts when the playlist holds it, else where the stream planned.
 */
internal fun adBreakAt(tags: List<String>, mediaSequence: Long, startsMs: List<Long>, durationsMs: List<Long>, positionMs: Long): AdBreakNow? {
    if (startsMs.isEmpty() || startsMs.size != durationsMs.size) return null
    val runs = tags.mapNotNull(AdRun::parse)
    if (runs.isEmpty()) return null
    val index = startsMs.indices.lastOrNull { startsMs[it] <= positionMs } ?: return null
    if (index == startsMs.lastIndex && positionMs >= startsMs[index] + durationsMs[index]) return null
    val sequence = mediaSequence + index
    val run = runs.firstOrNull { sequence in it.first..it.last } ?: return null
    val showIndex = (run.last + 1 - mediaSequence).toInt()
    val endMs = startsMs.getOrNull(showIndex)
        ?: run.endsAfterMs?.let { ends -> startsMs.getOrNull((run.first - mediaSequence).toInt())?.plus(ends) }
    return AdBreakNow(endMs?.let { (it - positionMs).coerceAtLeast(0) })
}
