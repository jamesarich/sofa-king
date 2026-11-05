package dev.jamesrich.sofaking.data

import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * What an engine that reads the playlist text itself needs from a live HLS playlist: the clock of its first segment
 * ([programDateTimeMs], null when the playlist has none), the segments' numbering and lengths, the ad [marks] and, for
 * a multivariant root, the first variant's URL.
 */
data class LivePlaylist(
    val programDateTimeMs: Long?,
    val mediaSequence: Long,
    val targetDurationMs: Long,
    val segmentDurationsMs: List<Long>,
    val marks: List<BreakMark>,
    val variantUrl: String?,
) {
    /** The program date time at the end of the last segment, which the newest picture sits at; null without a clock. */
    val liveEdgeMs: Long? get() = programDateTimeMs?.let { it + segmentDurationsMs.sum() }
}

private const val MS_PER_SECOND = 1000.0
private val OFFSET_WITHOUT_COLON = Regex("""([+-]\d{2})(\d{2})$""")

/**
 * Reads the live playlist [text]. A multivariant root has no segments; its first `#EXT-X-STREAM-INF` URI is
 * [LivePlaylist.variantUrl], resolved against [url] when given.
 */
fun parseLivePlaylist(text: String, url: String? = null): LivePlaylist {
    var pdt: Long? = null
    var sequence = 0L
    var target = 0L
    var variant: String? = null
    var nextIsVariant = false
    val durations = ArrayList<Long>()
    val marks = ArrayList<BreakMark>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            nextIsVariant && line.isNotEmpty() && !line.startsWith("#") -> {
                variant = url?.let { resolvePlaylistUrl(it, line) } ?: line
                nextIsVariant = false
            }

            line.startsWith("#EXT-X-STREAM-INF") -> nextIsVariant = variant == null

            line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") ->
                if (pdt == null) pdt = parseProgramDateTime(line.substringAfter(':'))

            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L

            line.startsWith("#EXT-X-TARGETDURATION:") ->
                target = (line.substringAfter(':').trim().toDoubleOrNull() ?: 0.0).times(MS_PER_SECOND).roundToLong()

            line.startsWith("#EXTINF:") ->
                durations += (line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0)
                    .times(MS_PER_SECOND).roundToLong()

            else -> parseBreakMark(line)?.let(marks::add)
        }
    }
    return LivePlaylist(pdt, sequence, target, durations, marks, variant)
}

private fun parseProgramDateTime(value: String): Long? {
    val text = value.trim().replace(OFFSET_WITHOUT_COLON, "$1:$2")
    return runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
}

/** [reference], as a playlist at [base] lists it: absolute, root-relative or relative to [base]'s directory. */
internal fun resolvePlaylistUrl(base: String, reference: String): String {
    if ("://" in reference) return reference
    val path = base.substringBefore('?').substringBefore('#')
    if (reference.startsWith("/")) {
        val authorityStart = path.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return reference
        val pathStart = path.indexOf('/', authorityStart).takeIf { it >= 0 } ?: path.length
        return path.substring(0, pathStart) + reference
    }
    return path.substringBeforeLast('/') + "/" + reference
}
