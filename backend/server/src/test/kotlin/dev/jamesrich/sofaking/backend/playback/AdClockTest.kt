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

    /** Whale's (sofast): one cue before a two-minute break and nothing on the segments after it. */
    private val whaleBase = URI("https://streams2.sofast.tv/sofast3/v1/master/peekflick/p1_tmpl3k_1k.m3u8")
    private fun whale(first: Int, vararg tags: Pair<Int, String>): String {
        val marks = tags.toMap()
        return "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n" +
            (first until first + 4).joinToString("") { (marks[it]?.let { tag -> "$tag\n" } ?: "") + "#EXTINF:6,\nseg-$it.ts\n" }
    }

    @Test
    fun `a cue that left the window keeps its break marked until the cue-in`() {
        val clock = AdClock()
        assertTrue(clock.annotate(whale(100, 102 to "#EXT-X-CUE-OUT:DURATION=120"), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=102,LAST=103,ENDS=120.000\n"))
        // 104 starts 24 s in, 12 s after the break began, so 108 s of it are left.
        assertTrue(clock.annotate(whale(104), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=104,LAST=107,ENDS=108.000\n"))
        assertTrue(clock.annotate(whale(106, 108 to "#EXT-X-CUE-IN"), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=106,LAST=107,ENDS=96.000\n"))
        assertEquals(whale(110), clock.annotate(whale(110), whaleBase))
    }

    @Test
    fun `a carried cue ends at its planned end without a cue-in`() {
        val clock = AdClock()
        clock.annotate(whale(100, 102 to "#EXT-X-CUE-OUT:12"), whaleBase)
        assertTrue(clock.annotate(whale(103), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=103,LAST=103,ENDS=6.000\n"))
        assertEquals(whale(106), clock.annotate(whale(106), whaleBase))
    }

    private fun whaleSegment(sequence: Int) = "https://streams2.sofast.tv/sofast3/v1/master/peekflick/seg-$sequence.ts"

    @Test
    fun `a cue inside a fetched segment marks the segments its break covers from its splice time`() {
        val clock = AdClock()
        assertEquals(whale(100), clock.annotate(whale(100), whaleBase))
        // 101 starts 6 s in; its cue splices 3 s after its first frame for 12 s, so the break runs 9 s to 21 s.
        clock.served(whaleSegment(101), SegmentCues(900_000, listOf(Splice(true, 1_170_000, 12_000))))
        assertEquals(AdBreakState(inBreak = true, remainingMs = 6_000), clock.at(aheadMs = 0))
        assertTrue(clock.annotate(whale(101), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=101,LAST=102,ENDS=15.000\n"))
        assertEquals(whale(104), clock.annotate(whale(104), whaleBase))
    }

    @Test
    fun `a cue sent again names the same break and a return ends it early`() {
        val clock = AdClock()
        clock.annotate(whale(100), whaleBase)
        clock.served(whaleSegment(100), SegmentCues(null, listOf(Splice(true, null, 60_000))))
        clock.served(whaleSegment(101), SegmentCues(540_000, listOf(Splice(true, 0, 60_000))))
        assertTrue(clock.annotate(whale(100), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=100,LAST=103,ENDS=60.000\n"))
        clock.served(whaleSegment(102), SegmentCues(null, listOf(Splice(false, null, null))))
        assertTrue(clock.annotate(whale(100), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=100,LAST=101,ENDS=12.000\n"))
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }
}
