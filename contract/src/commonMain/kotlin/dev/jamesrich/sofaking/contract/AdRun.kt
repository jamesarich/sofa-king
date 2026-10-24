package dev.jamesrich.sofaking.contract

import kotlin.math.roundToLong

/**
 * A run of ad segments in a live HLS media playlist, which the server marks in the playlists it relays to the app as
 * `#EXT-X-SOFA-AD:FIRST=<msn>,LAST=<msn>[,ENDS=<seconds>]`: the media sequence numbers of the run's first and last
 * segment, and, when the stream plans the break's length, where it ends in seconds after the first segment begins.
 * Players ignore a tag they do not know, so a playlist carrying it plays anywhere.
 */
data class AdRun(val first: Long, val last: Long, val endsAfterMs: Long? = null) {
    fun tag(): String = buildString {
        append(TAG).append(":FIRST=").append(first).append(",LAST=").append(last)
        if (endsAfterMs != null) {
            val millis = (endsAfterMs % MS_PER_SECOND).toString().padStart(MILLIS_DIGITS, '0')
            append(",ENDS=").append(endsAfterMs / MS_PER_SECOND).append('.').append(millis)
        }
    }

    companion object {
        const val TAG = "#EXT-X-SOFA-AD"
        private const val MS_PER_SECOND = 1000L
        private const val MILLIS_DIGITS = 3

        /** The run [line] marks, or null when it is another tag or does not read. */
        fun parse(line: String): AdRun? {
            if (!line.startsWith("$TAG:")) return null
            val values = line.substringAfter(':').split(',').associate {
                it.substringBefore('=').trim() to
                    it.substringAfter('=', "").trim()
            }
            val first = values["FIRST"]?.toLongOrNull() ?: return null
            val last = values["LAST"]?.toLongOrNull()?.takeIf { it >= first } ?: return null
            val ends = values["ENDS"]?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * MS_PER_SECOND).roundToLong() }
            return AdRun(first, last, ends)
        }
    }
}
