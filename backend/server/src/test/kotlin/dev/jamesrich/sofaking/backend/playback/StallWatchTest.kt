package dev.jamesrich.sofaking.backend.playback

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StallWatchTest {
    private var now = 0L
    private val watch = StallWatch({ now })
    private val source = URI("http://example.test/live/index.m3u8")

    private fun playlist(vararg segments: Int, target: Int = 4, ended: Boolean = false) = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:${segments.firstOrNull() ?: 0}\n")
        for (segment in segments) append("#EXTINF:$target,\nsegment-$segment.ts\n")
        if (ended) append("#EXT-X-ENDLIST\n")
    }

    @Test fun `a playlist that keeps adding segments never stalls`() {
        for (step in 0 until 10) {
            now = step * 4_000L
            assertFalse(watch.stalled(playlist(step, step + 1, step + 2), source))
        }
        assertFalse(watch.tripped)
    }

    @Test fun `a playlist with the same newest segment for two target durations stalls`() {
        assertFalse(watch.stalled(playlist(1, 2, 3), source))
        now = 8_000
        assertFalse(watch.stalled(playlist(1, 2, 3), source), "two target durations is not past them")
        now = 8_001
        assertTrue(watch.stalled(playlist(1, 2, 3), source))
        assertTrue(watch.tripped)
        assertFalse(watch.stalled(playlist(2, 3, 4), source), "a new segment clears the stall")
        assertTrue(watch.tripped, "the watch stays tripped")
    }

    @Test fun `a lapse with no segments counts as no new segment`() {
        watch.stalled(playlist(1, 2, 3), source)
        now = 9_000
        assertTrue(watch.stalled("#EXTM3U\n#EXT-X-ENDLIST\n", source))
    }

    @Test fun `master playlists, ended playlists and unseen ones never stall`() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvariant.m3u8\n"
        assertFalse(watch.stalled(master, source))
        now = 60_000
        assertFalse(watch.stalled(master, source))
        val ended = URI("http://example.test/vod.m3u8")
        watch.stalled(playlist(1, 2), ended)
        now = 120_000
        assertFalse(watch.stalled(playlist(1, 2, ended = true), ended))
        assertFalse(watch.stalled("#EXTM3U\n#EXT-X-ENDLIST\n", URI("http://example.test/new.m3u8")))
    }

    @Test fun `a target duration is kept between 2 and 30 seconds`() {
        watch.stalled(playlist(1, target = 1), source)
        now = 4_001
        assertTrue(watch.stalled(playlist(1, target = 1), source), "a 1 s target counts as 2 s")
        val long = URI("http://example.test/long.m3u8")
        watch.stalled(playlist(1, target = 90), long)
        now += 60_000
        assertFalse(watch.stalled(playlist(1, target = 90), long), "a 90 s target counts as 30 s")
        now += 1
        assertTrue(watch.stalled(playlist(1, target = 90), long))
    }
}
