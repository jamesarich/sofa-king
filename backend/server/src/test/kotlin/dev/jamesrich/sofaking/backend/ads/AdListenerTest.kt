package dev.jamesrich.sofaking.backend.ads

import dev.jamesrich.sofaking.backend.store.AdChannelRecord
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AdListenerTest {
    private val now = Instant.parse("2026-10-23T18:00:00Z")
    private fun record(id: String, watchedDaysAgo: Long?, marked: Boolean, heardMinutesAgo: Long? = null) =
        AdChannelRecord(
            id, "p", 0,
            watchedDaysAgo?.let {
                now.minusSeconds(it * 86_400)
            },
            0, 0, if (marked) now else null, 0, heardMinutesAgo?.let { now.minusSeconds(it * 60) },
        )

    @Test
    fun `channels watched lately without markers come first, then watched with markers, then unheard, then least recently heard`() {
        val known = listOf(
            record("old-unmarked", watchedDaysAgo = 30, marked = false, heardMinutesAgo = 600),
            record("unmarked-yesterday", watchedDaysAgo = 1, marked = false),
            record("unmarked-today", watchedDaysAgo = 0, marked = false),
            record("unmarked-just-heard", watchedDaysAgo = 0, marked = false, heardMinutesAgo = 5),
            record("marked-today", watchedDaysAgo = 0, marked = true),
            record("heard-long-ago", watchedDaysAgo = null, marked = false, heardMinutesAgo = 9_000),
        )
        val lineup = known.map { it.channelId } + "never-heard" + "watched-now"
        assertEquals(
            listOf(
                "unmarked-today",
                "unmarked-yesterday",
                "unmarked-just-heard",
                "marked-today",
                "never-heard",
                "heard-long-ago",
                "old-unmarked",
            ),
            AdListener.pick(known, lineup, busy = setOf("watched-now"), now = now),
        )
        assertEquals(
            listOf("unmarked-today", "unmarked-yesterday"),
            AdListener.pick(known, lineup, emptySet(), now = now).take(2),
        )
    }

    @Test
    fun `the lowest variant is followed, or its audio rendition`() {
        val base = URI("https://cdn.example.test/live/master.m3u8")
        val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="audio/en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=3000000,AUDIO="aac"
hi/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="aac"
lo/index.m3u8
"""
        assertEquals("https://cdn.example.test/live/audio/en.m3u8", AdListener.mediaOf(master, base))
        assertEquals(
            "https://cdn.example.test/live/lo/index.m3u8",
            AdListener.mediaOf(master.replace("AUDIO=\"aac\"", ""), base),
        )
        assertEquals(base.toString(), AdListener.mediaOf("#EXTM3U\n#EXTINF:6,\na.ts\n", base))
    }
}
