package dev.jamesrich.sofaking.backend.playback

import java.net.URI

/**
 * Keeps a live stream going through a source's empty answer. Pluto now and then answers a live media playlist with no
 * segments and `#EXT-X-ENDLIST`, which tells every player the stream is over: Media3 plays out its buffer and fails, and
 * the app tunes again. The last playlist from that URL that held segments goes out in its place; the player sees no new
 * segments and asks again on its next refresh, which catches up.
 */
class SteadyPlaylists(private val limit: Int = 16) {
    private val last = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > limit
    }

    /** [text], or the last media playlist from [source] that held segments when [text] is an empty, ended one. */
    @Synchronized
    fun steady(text: String, source: URI): String {
        val key = source.toString()
        val segments = text.contains("#EXTINF")
        if (!segments && text.contains("#EXT-X-ENDLIST")) return last[key] ?: text
        if (segments && !text.contains("#EXT-X-ENDLIST")) last[key] = text
        return text
    }
}
