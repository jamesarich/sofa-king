package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.MS_PER_SECOND
import dev.jamesrich.sofaking.backend.NS_PER_MS
import java.net.URI

/**
 * Tells a live source that stopped adding segments, per media playlist URL as a reader fetches it: a playlist whose
 * newest segment has not changed for two of its target durations has stalled. A playlist with no segments (a lapse)
 * adds none, so it stalls too; a master playlist, and one that ended (`#EXT-X-ENDLIST` with segments), never do.
 * Once a playlist stalls, [tripped] stays true.
 */
class StallWatch(private val now: () -> Long = { System.nanoTime() / NS_PER_MS }, private val limit: Int = 16) {
    private class Seen(var newest: String, var advancedAt: Long, var targetMs: Long)

    private val seen = object : LinkedHashMap<String, Seen>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>) = size > limit
    }

    /** Whether any playlist this watch saw has stalled. */
    @Volatile var tripped = false
        private set

    /** Whether [text], the media playlist fetched from [source], shows its source stalled. */
    @Synchronized
    fun stalled(text: String, source: URI): Boolean {
        val key = source.toString()
        val newest = newestSegment(text)
        if (text.contains(ENDLIST) && newest != null) {
            seen.remove(key)
            return false
        }
        val time = now()
        val last = seen[key]
        if (newest != null && newest != last?.newest) {
            seen[key] = Seen(newest, time, targetMs(text))
            return false
        }
        val stalled = last != null && time - last.advancedAt > STALL_TARGETS * last.targetMs
        if (stalled) tripped = true
        return stalled
    }

    private companion object {
        const val ENDLIST = "#EXT-X-ENDLIST"
        const val TARGET = "#EXT-X-TARGETDURATION:"
        const val STALL_TARGETS = 2

        /** The target duration a playlist without a valid one is taken to have, and the range one is kept in. */
        const val DEFAULT_TARGET_SECONDS = 6L
        val TARGET_SECONDS = 2L..30L

        /** The URI of the playlist's last segment; null when it lists none. */
        fun newestSegment(text: String): String? {
            if (!text.contains("#EXTINF")) return null
            return text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") }
        }

        fun targetMs(text: String): Long {
            val seconds = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(TARGET) }
                ?.substringAfter(':')?.trim()?.toLongOrNull() ?: DEFAULT_TARGET_SECONDS
            return seconds.coerceIn(TARGET_SECONDS) * MS_PER_SECOND
        }
    }
}
