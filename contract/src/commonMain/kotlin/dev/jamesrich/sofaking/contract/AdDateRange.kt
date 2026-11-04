package dev.jamesrich.sofaking.contract

import kotlin.math.roundToLong

/**
 * An ad break in a live HLS media playlist as the standard `#EXT-X-DATERANGE` tag, which the server adds beside each
 * [AdRun] tag: `CLASS` is [CLASS], `START-DATE` is when the break begins on the playlist's `#EXT-X-PROGRAM-DATE-TIME`
 * clock, and `DURATION` (when the break's end is in the playlist) or `PLANNED-DURATION` (when the stream says how long
 * it runs) is in seconds. The `ID` is stable across refreshes, so a player updates one range as the break grows.
 */
data class AdDateRange(
    val id: String,
    val startDate: String,
    val durationMs: Long? = null,
    val plannedDurationMs: Long? = null,
) {
    companion object {
        const val CLASS = "dev.jamesrich.sofaking.ad"
        const val TAG = "#EXT-X-DATERANGE"
        private const val MS_PER_SECOND = 1000L
        private const val MILLIS_DIGITS = 3
        private val ATTRIBUTE = Regex("""([A-Za-z0-9-]+)=("[^"]*"|[^,]*)""")

        /** The tag for a break [id] starting at [startDate], an ISO-8601 instant, with the lengths it has, if any. */
        fun tag(id: String, startDate: String, durationMs: Long? = null, plannedDurationMs: Long? = null): String =
            buildString {
                append(TAG).append(":ID=\"").append(id).append("\",CLASS=\"").append(CLASS)
                append("\",START-DATE=\"").append(startDate).append('"')
                if (durationMs != null) append(",DURATION=").append(seconds(durationMs))
                if (plannedDurationMs != null) append(",PLANNED-DURATION=").append(seconds(plannedDurationMs))
            }

        /** The range [line] marks, or null when it is another tag, another class, or does not read. */
        fun parse(line: String): AdDateRange? {
            if (!line.startsWith("$TAG:")) return null
            val values = ATTRIBUTE.findAll(line.substringAfter(':')).associate {
                it.groupValues[1] to it.groupValues[2].trim().trim('"')
            }
            if (values["CLASS"] != CLASS) return null
            val id = values["ID"]?.takeIf { it.isNotEmpty() } ?: return null
            val start = values["START-DATE"]?.takeIf { it.isNotEmpty() } ?: return null
            return AdDateRange(id, start, millis(values["DURATION"]), millis(values["PLANNED-DURATION"]))
        }

        private fun seconds(ms: Long): String {
            val whole = ms / MS_PER_SECOND
            val fraction = (ms % MS_PER_SECOND).toString().padStart(MILLIS_DIGITS, '0').trimEnd('0')
            return if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
        }

        private fun millis(value: String?): Long? =
            value?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * MS_PER_SECOND).roundToLong() }
    }
}
