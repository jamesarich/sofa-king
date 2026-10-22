package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.contract.AdBreakState
import java.net.URI
import kotlin.test.*

class AdClockTest {
    private val base = URI("https://aegis-cloudfront-1.tubi.video/b18a776e/master_5.m3u8")
    private fun segment(name: Int) = "https://aegis-cloudfront-1.tubi.video/b18a776e/master_5_$name.ts"

    /** Tubi's: two show segments (5535769 and 5535770), then a two-minute break planned from 5535771. */
    private val tubi = """#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:5535769
#EXTINF:6.006,
master_5_5535910.ts
#EXTINF:0.601,
master_5_5535911.ts
#EXT-X-CUE-OUT:120.020
#EXTINF:5.406,
master_5_5535912.ts
#EXT-X-CUE-OUT-CONT:ElapsedTime=5.406,Duration=120.020
#EXTINF:6.006,
master_5_5535913.ts
"""

    @Test
    fun `the picture is placed back from the last segment fetched by what the player loaded ahead`() {
        val clock = AdClock()
        assertTrue(clock.annotate(tubi, base).contains("#EXT-X-SOFA-AD:FIRST=5535771,LAST=5535772,ENDS=120.020"))
        clock.served(segment(5535913))
        // The last segment starts 12,013 ms in and lasts 6,006; the break was planned to end 120,020 ms after 6,607.
        assertEquals(AdBreakState(inBreak = true, remainingMs = 111_608), clock.at(aheadMs = 3_000))
        assertEquals(AdBreakState(inBreak = true, remainingMs = 118_608), clock.at(aheadMs = 10_000))
        assertEquals(AdBreakState(), clock.at(aheadMs = 14_000))
    }

    @Test
    fun `the show segment after the break ends it once a later playlist holds it`() {
        val clock = AdClock()
        clock.annotate(tubi, base)
        clock.annotate(tubi.replace("#EXT-X-MEDIA-SEQUENCE:5535769\n#EXTINF:6.006,\nmaster_5_5535910.ts\n", "#EXT-X-MEDIA-SEQUENCE:5535770\n") +
            "#EXT-X-CUE-IN\n#EXTINF:6.006,\nmaster_5_5535914.ts\n", base)
        clock.served(segment(5535914))
        assertEquals(AdBreakState(inBreak = true, remainingMs = 994), clock.at(aheadMs = 7_000))
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }

    @Test
    fun `nothing is known before a marked segment is fetched or for a segment of another playlist`() {
        val clock = AdClock()
        clock.annotate(tubi, base)
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
        clock.served("https://aegis-cloudfront-1.tubi.video/b18a776e/audio_1.aac")
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }

    @Test
    fun `a window that skips ahead starts the timeline again`() {
        val clock = AdClock()
        clock.annotate(tubi, base)
        clock.annotate("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5536000\n#EXTINF:6,\nlater.ts\n", base)
        clock.served(segment(5535913))
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }
}
