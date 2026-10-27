package dev.jamesrich.sofaking.backend.multiview

/**
 * A multiview stream's live playlist as players get it. FFmpeg's HLS muxer marks the stream's first segment with
 * `#EXT-X-DISCONTINUITY`, twice and around `#EXT-X-INDEPENDENT-SEGMENTS`, and counts the discontinuities that left
 * the window nowhere. AVFoundation compares every reload with the last and fails the stream with CoreMedia -12312
 * ("Media Entry discontinuity value does not match previous playlist"). So the first segment's marks go, playlist tags
 * move above the segments, one mark stands for a run of them, and `#EXT-X-DISCONTINUITY-SEQUENCE` counts the ones
 * before the window, as this playlist has seen them.
 */
internal class MultiviewPlaylist {
    /** The media sequence numbers of the segments a discontinuity comes before. */
    private val breaks = sortedSetOf<Long>()

    @Synchronized
    fun serve(text: String): String {
        val lines = text.lines()
        val first = lines.firstOrNull { it.startsWith(SEQUENCE) }?.removePrefix(SEQUENCE)?.trim()?.toLongOrNull()
            ?: return text
        val header = ArrayList<String>()
        val body = ArrayList<String>()
        var sequence = first
        var broken = false
        for (line in lines) {
            when {
                line.isBlank() -> Unit

                line == DISCONTINUITY -> broken = true

                line.startsWith("#EXTM3U") || line.startsWith(SEQUENCE) -> Unit

                line.startsWith("#EXT-X-") && line.substringBefore(':') in PLAYLIST_TAGS -> header += line

                else -> {
                    val segment = !line.startsWith('#')
                    if (broken && sequence > 0) {
                        body += DISCONTINUITY
                        breaks += sequence
                    }
                    broken = false
                    body += line
                    if (segment) sequence++
                }
            }
        }
        val before = breaks.headSet(first).size
        val counted = if (before > 0) listOf("$DISCONTINUITY_SEQUENCE$before") else emptyList()
        return (listOf("#EXTM3U") + header + "$SEQUENCE$first" + counted + body).joinToString("\n", postfix = "\n")
    }

    private companion object {
        const val SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
        const val DISCONTINUITY = "#EXT-X-DISCONTINUITY"
        const val DISCONTINUITY_SEQUENCE = "#EXT-X-DISCONTINUITY-SEQUENCE:"

        /** Tags of the whole playlist rather than of a segment. */
        val PLAYLIST_TAGS = setOf(
            "#EXT-X-VERSION",
            "#EXT-X-TARGETDURATION",
            "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-PLAYLIST-TYPE",
            "#EXT-X-START",
        )
    }
}
