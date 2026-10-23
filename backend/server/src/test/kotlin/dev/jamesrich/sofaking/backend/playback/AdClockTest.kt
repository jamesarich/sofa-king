package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
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

    private val loud = FloatArray(60) { -20f }
    private val silent = FloatArray(60) { -90f }

    /** A clock learning as [mode], with what it reported. */
    private fun learning(mode: LearnedBreaks): Pair<AdClock, MutableList<LearnedBreak>> {
        val found = mutableListOf<LearnedBreak>()
        return AdClock(learning = { mode }, learned = { found += it }) to found
    }

    private fun AdClock.hear(vararg levels: Pair<Int, FloatArray>) = levels.forEach { (sequence, level) -> heard(whaleSegment(sequence), level) }

    @Test
    fun `twenty seconds of silence is a slate break, marked when learning is on`() {
        val (clock, found) = learning(LearnedBreaks.ON)
        clock.annotate(whale(100), whaleBase)
        clock.annotate(whale(102), whaleBase)
        clock.hear(100 to loud, 101 to silent, 102 to silent, 103 to silent)
        assertEquals(emptyList(), found, "18 s is not yet a slate")
        clock.hear(104 to silent)
        assertEquals(listOf(LearnedBreak(SLATE, 24_000, ended = false, marked = true)), found)
        assertTrue(clock.annotate(whale(102), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=102,LAST=104\n"))
        // Sound half way into 105 ends it there, 27 s after it began.
        clock.hear(105 to FloatArray(60) { if (it < 30) -90f else -20f })
        assertEquals(LearnedBreak(SLATE, 27_000, ended = true, marked = true), found.last())
        assertTrue(clock.annotate(whale(102), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=102,LAST=104,ENDS=21.000\n"))
        // The break's last whole segment ends 30 s in; the picture is 1 s short of it.
        clock.served(whaleSegment(104))
        assertEquals(AdBreakState(inBreak = true, remainingMs = 1_000), clock.at(aheadMs = 1_000))
    }

    @Test
    fun `in shadow a slate is reported and nothing is marked`() {
        val (clock, found) = learning(LearnedBreaks.SHADOW)
        clock.annotate(whale(100), whaleBase)
        clock.annotate(whale(104), whaleBase)
        clock.hear(101 to silent, 102 to silent, 103 to silent, 104 to silent, 105 to loud)
        assertEquals(listOf(LearnedBreak(SLATE, 24_000, ended = false, marked = false), LearnedBreak(SLATE, 24_000, ended = true, marked = false)), found)
        assertFalse(clock.annotate(whale(102), whaleBase).contains("SOFA-AD"))
        clock.served(whaleSegment(104))
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }

    @Test
    fun `a shorter silence is only reported, a segment heard out of order still joins, and off hears nothing`() {
        val (clock, found) = learning(LearnedBreaks.ON)
        clock.annotate(whale(100), whaleBase)
        clock.hear(100 to loud, 102 to silent, 101 to silent, 103 to loud)
        assertEquals(listOf(LearnedBreak(SHORT_SLATE, 12_000, ended = true, marked = false)), found)
        assertFalse(clock.annotate(whale(100), whaleBase).contains("SOFA-AD"))

        val (off, none) = learning(LearnedBreaks.OFF)
        off.annotate(whale(100), whaleBase)
        off.annotate(whale(104), whaleBase)
        off.hear(*(100..107).map { it to silent }.toTypedArray())
        assertEquals(emptyList(), none)
        assertFalse(off.listening)
    }

    @Test
    fun `a playlist that carries a marker is never learned from`() {
        val (clock, found) = learning(LearnedBreaks.ON)
        clock.annotate(whale(100, 100 to "#EXT-X-CUE-OUT:DURATION=12"), whaleBase)
        clock.annotate(whale(104, 106 to "#EXT-X-CUE-IN"), whaleBase)
        clock.hear(*(100..107).map { it to silent }.toTypedArray())
        assertEquals(emptyList(), found)
    }

    @Test
    fun `the census counts the media played once, the marked breaks and the slates`() {
        val marked = AdClock()
        marked.annotate(whale(100, 101 to "#EXT-X-CUE-OUT:DURATION=6", 102 to "#EXT-X-CUE-IN"), whaleBase)
        (100..103).forEach { marked.served(whaleSegment(it)) }
        marked.served(whaleSegment(103))
        assertEquals(AdCensus(playedMs = 24_000, marked = true, breaks = 1, learned = 0), marked.census())

        val (learned, _) = learning(LearnedBreaks.SHADOW)
        learned.annotate(whale(100), whaleBase)
        learned.annotate(whale(104), whaleBase)
        learned.hear(*(101..104).map { it to silent }.toTypedArray())
        learned.served(whaleSegment(100))
        assertEquals(AdCensus(playedMs = 6_000, marked = false, breaks = 0, learned = 1), learned.census())
    }

    private val adAudio = kotlin.random.Random(11).let { random -> IntArray(1_000) { random.nextInt() } }
    private val noise = kotlin.random.Random(12)

    /**
     * Hears [sequences] of a 6 s playlist whose first segment is [first], with the ad's audio from [adFromMs] to
     * [adUntilMs] on its timeline and other audio elsewhere.
     */
    private fun AdClock.listen(first: Int, sequences: IntRange, adFromMs: Long, adUntilMs: Long) {
        val step = BreakLibrary.POINT_MS
        for (sequence in sequences) {
            val from = kotlin.math.ceil((sequence - first) * 6_000 / step).toInt()
            val until = kotlin.math.ceil((sequence - first + 1) * 6_000 / step).toInt()
            val words = IntArray(until - from) { i ->
                val at = (from + i) * step
                if (at >= adFromMs && at < adUntilMs) adAudio[((at - adFromMs) / step).toInt()] else noise.nextInt()
            }
            heard(whaleSegment(sequence), loud, words, kotlin.math.round(from * step).toLong())
        }
    }

    /** A clock on [channel] that learns into [library] as [mode], with what it reported. */
    private fun learning(mode: LearnedBreaks, library: BreakLibrary, channel: String): Pair<AdClock, MutableList<LearnedBreak>> {
        val found = mutableListOf<LearnedBreak>()
        return AdClock(learning = { mode }, library = library, channelId = channel, providerId = "whale", learned = { found += it }) to found
    }

    /** A marked break from 6 to 36 s, closed by its cue-in, heard on [channel]. */
    private fun learnBreak(library: BreakLibrary, channel: String) {
        val (marked, _) = learning(LearnedBreaks.SHADOW, library, channel)
        marked.annotate(whale(100, 101 to "#EXT-X-CUE-OUT:DURATION=30"), whaleBase)
        marked.annotate(whale(104, 106 to "#EXT-X-CUE-IN"), whaleBase)
        marked.listen(100, 100..107, adFromMs = 6_000, adUntilMs = 36_000)
    }

    @Test
    fun `a break markers closed is learned, and its ads start a learned break on a channel without markers`() {
        val library = BreakLibrary(null, java.time.Clock.systemUTC())
        learnBreak(library, "c1")
        // 3 s in from each edge of the 30 s break.
        assertEquals(1, library.stats().entries)
        assertEquals(194L, library.stats().points)

        val (shadow, found) = learning(LearnedBreaks.ON, library, "c3")
        shadow.annotate(whale(200), whaleBase)
        shadow.annotate(whale(204), whaleBase)
        shadow.listen(200, 200..203, adFromMs = 6_000, adUntilMs = 36_000)
        assertEquals(listOf(LIBRARY), found.map { it.rule })
        assertFalse(found.single().marked, "an entry marked on one channel one day is not yet trusted to blank")
        assertFalse(shadow.annotate(whale(204), whaleBase).contains("SOFA-AD"))
        shadow.listen(200, 204..207, adFromMs = 6_000, adUntilMs = 36_000)
        val ended = found.last()
        assertTrue(ended.ended)
        // From 3 s into the ad, where the entry starts, to 3 s before its end.
        assertEquals(24_000.0, ended.lengthMs.toDouble(), 300.0)
        assertEquals(AdCensus(playedMs = 0, marked = false, breaks = 0, learned = 1), shadow.census())
    }

    @Test
    fun `an entry marked on two channels is marked for the player, while the canary is good`() {
        val library = BreakLibrary(null, java.time.Clock.systemUTC())
        learnBreak(library, "c1")
        learnBreak(library, "c2")
        assertEquals(1, library.stats().blanking)
        val (on, found) = learning(LearnedBreaks.ON, library, "c3")
        on.annotate(whale(200), whaleBase)
        on.annotate(whale(204), whaleBase)
        on.listen(200, 200..203, adFromMs = 6_000, adUntilMs = 36_000)
        assertTrue(found.single().marked, found.toString())
        // The match starts just past 9 s, the middle of segment 201, so the marks start with 202: a little ad shows, never show.
        val marks = on.annotate(whale(200), whaleBase)
        assertTrue(marks.endsWith("#EXT-X-SOFA-AD:FIRST=202,LAST=203\n"), marks)
    }

    @Test
    fun `the canary counts a match in a marked break and blocks an entry heard in the show`() {
        val library = BreakLibrary(null, java.time.Clock.systemUTC())
        learnBreak(library, "c1")
        learnBreak(library, "c2")
        val entry = library.match(adAudio.copyOfRange(30, 200), BreakLibrary.MIN_LEARN_POINTS)!!.entry
        assertEquals(1, entry.hits, "the second channel's canary found the first's entry in its break")
        val (marked, found) = learning(LearnedBreaks.ON, library, "c4")
        marked.annotate(whale(300, 301 to "#EXT-X-CUE-OUT:DURATION=30"), whaleBase)
        marked.annotate(whale(304, 306 to "#EXT-X-CUE-IN"), whaleBase)
        marked.annotate(whale(308), whaleBase)
        marked.annotate(whale(312), whaleBase)
        marked.listen(300, 300..307, adFromMs = 6_000, adUntilMs = 36_000)
        assertEquals(2, entry.hits)
        assertFalse(entry.blocked)
        // The same audio again at 60 s, long after the break ended: show audio, as far as the markers say.
        marked.listen(300, 308..315, adFromMs = 60_000, adUntilMs = 90_000)
        assertTrue(entry.blocked)
        assertTrue(library.frozen())
        assertEquals(listOf(CANARY), found.map { it.rule })
    }
}
