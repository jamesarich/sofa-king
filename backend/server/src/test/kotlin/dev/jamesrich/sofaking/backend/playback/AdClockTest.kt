package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.contract.AdBreakState
import dev.jamesrich.sofaking.contract.AdDateRange
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdClockTest {
    private val base = URI("https://aegis-cloudfront-1.tubi.video/b18a776e/master_5.m3u8")
    private fun segment(name: Int) = "https://aegis-cloudfront-1.tubi.video/b18a776e/master_5_$name.ts"

    /** [text] as the clock marked it, without the dates and date ranges that every playlist and break now gets. */
    private fun AdClock.plain(text: String, base: URI): String = annotate(text, base).lines()
        .filter { !it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") && !it.startsWith("#EXT-X-DATERANGE:") }
        .joinToString("\n")

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
        assertTrue(clock.plain(tubi, base).contains("#EXT-X-SOFA-AD:FIRST=5535771,LAST=5535772,ENDS=120.020"))
        clock.served(segment(5535913))
        // The last segment starts 12,013 ms in and lasts 6,006; the break was planned to end 120,020 ms after 6,607.
        assertEquals(AdBreakState(inBreak = true, remainingMs = 111_608), clock.at(aheadMs = 3_000))
        assertEquals(AdBreakState(inBreak = true, remainingMs = 118_608), clock.at(aheadMs = 10_000))
        assertEquals(AdBreakState(), clock.at(aheadMs = 14_000))
    }

    @Test
    fun `the show segment after the break ends it once a later playlist holds it`() {
        val clock = AdClock()
        clock.plain(tubi, base)
        clock.plain(
            tubi.replace(
                "#EXT-X-MEDIA-SEQUENCE:5535769\n#EXTINF:6.006,\nmaster_5_5535910.ts\n",
                "#EXT-X-MEDIA-SEQUENCE:5535770\n",
            ) +
                "#EXT-X-CUE-IN\n#EXTINF:6.006,\nmaster_5_5535914.ts\n",
            base,
        )
        clock.served(segment(5535914))
        assertEquals(AdBreakState(inBreak = true, remainingMs = 994), clock.at(aheadMs = 7_000))
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }

    @Test
    fun `nothing is known before a marked segment is fetched or for a segment of another playlist`() {
        val clock = AdClock()
        clock.plain(tubi, base)
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
        clock.served("https://aegis-cloudfront-1.tubi.video/b18a776e/audio_1.aac")
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }

    @Test
    fun `a session whose first playlist already ends in a marked break joined in the break`() {
        val joined = AdClock()
        joined.plain(tubi, base)
        assertTrue(joined.census().joinedInBreak)

        val before = AdClock()
        before.plain("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5535769\n#EXTINF:6.006,\nmaster_5_5535910.ts\n", base)
        before.plain(tubi, base)
        assertFalse(before.census().joinedInBreak)
    }

    @Test
    fun `a window that skips ahead starts the timeline again`() {
        val clock = AdClock()
        clock.plain(tubi, base)
        clock.plain("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5536000\n#EXTINF:6,\nlater.ts\n", base)
        clock.served(segment(5535913))
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }

    /** Whale's (sofast): one cue before a two-minute break and nothing on the segments after it. */
    private val whaleBase = URI("https://streams2.sofast.tv/sofast3/v1/master/peekflick/p1_tmpl3k_1k.m3u8")
    private fun whale(first: Int, vararg tags: Pair<Int, String>): String {
        val marks = tags.toMap()
        return "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n" +
            (first until first + 4).joinToString("") {
                (marks[it]?.let { tag -> "$tag\n" } ?: "") + "#EXTINF:6,\nseg-$it.ts\n"
            }
    }

    @Test
    fun `a cue that left the window keeps its break marked until the cue-in`() {
        val clock = AdClock()
        assertTrue(
            clock.plain(
                whale(100, 102 to "#EXT-X-CUE-OUT:DURATION=120"),
                whaleBase,
            ).endsWith("#EXT-X-SOFA-AD:FIRST=102,LAST=103,ENDS=120.000\n"),
        )
        // 104 starts 24 s in, 12 s after the break began, so 108 s of it are left.
        assertTrue(clock.plain(whale(104), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=104,LAST=107,ENDS=108.000\n"))
        assertTrue(
            clock.plain(
                whale(106, 108 to "#EXT-X-CUE-IN"),
                whaleBase,
            ).endsWith("#EXT-X-SOFA-AD:FIRST=106,LAST=107,ENDS=96.000\n"),
        )
        assertEquals(whale(110), clock.plain(whale(110), whaleBase))
    }

    @Test
    fun `a carried cue ends at its planned end without a cue-in`() {
        val clock = AdClock()
        clock.plain(whale(100, 102 to "#EXT-X-CUE-OUT:12"), whaleBase)
        assertTrue(clock.plain(whale(103), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=103,LAST=103,ENDS=6.000\n"))
        assertEquals(whale(106), clock.plain(whale(106), whaleBase))
    }

    private val dated = MutableClock()
    private val start = dated.now.toEpochMilli()
    private fun iso(epochMs: Long) = AdBreaks.isoMs(epochMs)

    /** A live playlist of [count] six second segments from [first], with each tag of [tags] before its segment. */
    private fun live(first: Int, count: Int, vararg tags: Pair<Int, String>): String {
        val marks = tags.toMap()
        return "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n" +
            (first until first + count).joinToString("") {
                (marks[it]?.let { tag -> "$tag\n" } ?: "") + "#EXTINF:6,\nseg-$it.ts\n"
            }
    }

    private fun dates(text: String) = text.lines().filter { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }
    private fun ranges(text: String) = text.lines().mapNotNull(AdDateRange::parse)

    @Test
    fun `a playlist with no dates gets one from the first sight of it, the same on every refresh`() {
        val clock = AdClock(clock = dated)
        val first = clock.annotate(live(100, 4), whaleBase)
        assertEquals(listOf("#EXT-X-PROGRAM-DATE-TIME:" + iso(start - 24_000)), dates(first))
        assertTrue(first.indexOf("#EXT-X-PROGRAM-DATE-TIME") < first.indexOf("#EXTINF"))
        dated.now = dated.now.plus(Duration.ofSeconds(30))
        // The window slid two segments, which began 12 s into the first one.
        assertEquals(
            listOf("#EXT-X-PROGRAM-DATE-TIME:" + iso(start - 12_000)),
            dates(clock.annotate(live(102, 4), whaleBase)),
        )
    }

    @Test
    fun `a date follows each discontinuity, and a playlist with dates of its own is left alone`() {
        val clock = AdClock(clock = dated)
        val text = live(100, 4).replace("#EXTINF:6,\nseg-102.ts", "#EXT-X-DISCONTINUITY\n#EXTINF:6,\nseg-102.ts")
        assertEquals(
            listOf(iso(start - 24_000), iso(start - 12_000)).map { "#EXT-X-PROGRAM-DATE-TIME:$it" },
            dates(clock.annotate(text, whaleBase)),
        )
        val own = live(200, 2).replace("#EXTINF", "#EXT-X-PROGRAM-DATE-TIME:2026-11-04T12:00:00.000Z\n#EXTINF")
        assertEquals(own, clock.annotate(own, URI("https://other.example.test/live.m3u8")))
    }

    @Test
    fun `a window that skips ahead takes a new date from the clock`() {
        val clock = AdClock(clock = dated)
        clock.annotate(live(100, 4), whaleBase)
        dated.now = dated.now.plus(Duration.ofSeconds(1000))
        assertEquals(
            listOf("#EXT-X-PROGRAM-DATE-TIME:" + iso(start + 1_000_000 - 24_000)),
            dates(clock.annotate(live(500, 4), whaleBase)),
        )
    }

    @Test
    fun `a date range keeps its start across refreshes and gains its duration once the show returns`() {
        val clock = AdClock(clock = dated)
        val open = clock.annotate(live(100, 4, 102 to "#EXT-X-CUE-OUT:DURATION=30"), whaleBase)
        val at = iso(start - 24_000 + 12_000)
        assertEquals(listOf(AdDateRange("sofa-ad-102", at, plannedDurationMs = 30_000)), ranges(open))
        dated.now = dated.now.plus(Duration.ofSeconds(12))
        val closed = clock.annotate(
            live(101, 6, 102 to "#EXT-X-CUE-OUT:DURATION=30", 105 to "#EXT-X-CUE-IN"),
            whaleBase,
        )
        assertEquals(listOf(AdDateRange("sofa-ad-102", at, durationMs = 18_000)), ranges(closed))
    }

    @Test
    fun `a break keeps one ID and start while the window slides past its first segment, and its close persists`() {
        val clock = AdClock(clock = dated)
        val at = iso(start - 24_000 + 12_000)
        val seen = ArrayList<List<AdDateRange>>()
        for (first in listOf(100, 103, 106, 108, 110, 112)) {
            val tags = if (first == 100) arrayOf(102 to "#EXT-X-CUE-OUT:DURATION=30") else emptyArray()
            seen += ranges(clock.annotate(live(first, 4, *tags), whaleBase))
        }
        assertEquals(listOf(AdDateRange("sofa-ad-102", at, plannedDurationMs = 30_000)), seen[0])
        assertEquals(listOf(AdDateRange("sofa-ad-102", at, plannedDurationMs = 30_000)), seen[1])
        // The window starting at 106 holds the break's last segment and the show after it: 102 to 107 is 30 s.
        val closed = AdDateRange("sofa-ad-102", at, durationMs = 30_000)
        assertEquals(listOf(closed), seen[2])
        // Its segments have left the window; the closed range stays for two more refreshes.
        assertEquals(listOf(closed), seen[3])
        assertEquals(listOf(closed), seen[4])
        assertEquals(emptyList(), seen[5])
    }

    @Test
    fun `two separate breaks get two IDs, and a reset forgets them`() {
        val clock = AdClock(clock = dated)
        clock.annotate(live(100, 4, 100 to "#EXT-X-CUE-OUT:DURATION=12"), whaleBase)
        val second = ranges(clock.annotate(live(104, 6, 107 to "#EXT-X-CUE-OUT:DURATION=12"), whaleBase)).sortedBy {
            it.id
        }
        assertEquals(listOf("sofa-ad-100", "sofa-ad-107"), second.map { it.id })
        // The first is closed by the window that moved on; the second is still open with its planned length.
        assertEquals(listOf(24_000L, null), second.map { it.durationMs })
        assertEquals(listOf(null, 12_000L), second.map { it.plannedDurationMs })
        // A window that skips ahead starts the timeline again, and with it the breaks.
        dated.now = dated.now.plus(Duration.ofSeconds(1000))
        assertEquals(emptyList(), ranges(clock.annotate(live(500, 4), whaleBase)))
        val fresh = ranges(clock.annotate(live(504, 4, 505 to "#EXT-X-CUE-OUT:DURATION=12"), whaleBase))
        assertEquals(listOf("sofa-ad-505"), fresh.map { it.id })
    }

    @Test
    fun `a playlist with dates of its own puts its date ranges on them`() {
        val clock = AdClock(clock = dated)
        val own = live(100, 4, 102 to "#EXT-X-CUE-OUT:DURATION=30")
            .replace(
                "#EXTINF:6,\nseg-100.ts",
                "#EXT-X-PROGRAM-DATE-TIME:2026-11-04T12:00:00.000Z\n#EXTINF:6,\nseg-100.ts",
            )
        assertEquals(
            listOf(AdDateRange("sofa-ad-102", "2026-11-04T12:00:12.000Z", plannedDurationMs = 30_000)),
            ranges(clock.annotate(own, whaleBase)),
        )
    }

    private fun whaleSegment(sequence: Int) = "https://streams2.sofast.tv/sofast3/v1/master/peekflick/seg-$sequence.ts"

    @Test
    fun `a cue inside a fetched segment marks the segments its break covers from its splice time`() {
        val clock = AdClock()
        assertEquals(whale(100), clock.plain(whale(100), whaleBase))
        // 101 starts 6 s in; its cue splices 3 s after its first frame for 12 s, so the break runs 9 s to 21 s.
        clock.served(whaleSegment(101), SegmentCues(900_000, listOf(Splice(true, 1_170_000, 12_000))))
        assertEquals(AdBreakState(inBreak = true, remainingMs = 6_000), clock.at(aheadMs = 0))
        assertTrue(clock.plain(whale(101), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=101,LAST=102,ENDS=15.000\n"))
        assertEquals(whale(104), clock.plain(whale(104), whaleBase))
    }

    @Test
    fun `a cue sent again names the same break and a return ends it early`() {
        val clock = AdClock()
        clock.plain(whale(100), whaleBase)
        clock.served(whaleSegment(100), SegmentCues(null, listOf(Splice(true, null, 60_000))))
        clock.served(whaleSegment(101), SegmentCues(540_000, listOf(Splice(true, 0, 60_000))))
        assertTrue(clock.plain(whale(100), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=100,LAST=103,ENDS=60.000\n"))
        clock.served(whaleSegment(102), SegmentCues(null, listOf(Splice(false, null, null))))
        assertTrue(clock.plain(whale(100), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=100,LAST=101,ENDS=12.000\n"))
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }

    private val loud = FloatArray(60) { -20f }
    private val silent = FloatArray(60) { -90f }

    /** A clock learning as [mode], with what it reported. */
    private fun learning(mode: LearnedBreaks): Pair<AdClock, MutableList<LearnedBreak>> {
        val found = mutableListOf<LearnedBreak>()
        return AdClock(learning = { mode }, learned = { found += it }) to found
    }

    private fun AdClock.hear(vararg levels: Pair<Int, FloatArray>) = levels.forEach { (sequence, level) ->
        heard(whaleSegment(sequence), level)
    }

    @Test
    fun `twenty seconds of silence is a slate break, marked when learning is on`() {
        val (clock, found) = learning(LearnedBreaks.ON)
        clock.plain(whale(100), whaleBase)
        clock.plain(whale(102), whaleBase)
        clock.hear(100 to loud, 101 to silent, 102 to silent, 103 to silent)
        assertEquals(emptyList(), found, "18 s is not yet a slate")
        clock.hear(104 to silent)
        assertEquals(listOf(LearnedBreak(SLATE, 24_000, ended = false, marked = true)), found)
        assertTrue(clock.plain(whale(102), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=102,LAST=104\n"))
        // Sound half way into 105 ends it there, 27 s after it began.
        clock.hear(105 to FloatArray(60) { if (it < 30) -90f else -20f })
        assertEquals(LearnedBreak(SLATE, 27_000, ended = true, marked = true), found.last())
        assertTrue(clock.plain(whale(102), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=102,LAST=104,ENDS=21.000\n"))
        // The break's last whole segment ends 30 s in; the picture is 1 s short of it.
        clock.served(whaleSegment(104))
        assertEquals(AdBreakState(inBreak = true, remainingMs = 1_000), clock.at(aheadMs = 1_000))
    }

    @Test
    fun `a slate heard as its segments close is marked in the next playlist before any is fetched`() {
        val time = MutableClock()
        val found = mutableListOf<LearnedBreak>()
        val clock = AdClock(learning = { LearnedBreaks.ON }, clock = time, learned = { found += it })
        // Each segment is listed and heard as it closes, 6 s apart, and the player has fetched none.
        val listed = intArrayOf(100, 101, 102, 103, 104)
        for (sequence in listed) {
            time.now = time.now.plusSeconds(6)
            clock.plain(whale(sequence - 2), whaleBase)
            clock.hear(sequence to if (sequence == 100) loud else silent)
        }
        assertEquals(listOf(LearnedBreak(SLATE, 24_000, ended = false, marked = true)), found)
        assertTrue(clock.plain(whale(102), whaleBase).endsWith("#EXT-X-SOFA-AD:FIRST=102,LAST=104\n"))
        assertEquals(AdBreakState(), clock.at(aheadMs = 0), "the picture is placed by what the player fetched")
    }

    @Test
    fun `the lead is the hold-back a playlist went out with, else three target durations, and none for its own`() {
        val clock = AdClock()
        assertEquals(null, clock.lead())
        clock.plain(window(100, 3), whaleBase)
        assertEquals(18_000L, clock.lead(), "a window with no room goes out unchanged: the default, 3 x 6 s")
        clock.plain(window(100, 10), whaleBase)
        assertEquals(36_000L, clock.lead())
        val own = AdClock()
        own.plain(window(100, 10, "#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES\n"), whaleBase)
        assertEquals(null, own.lead())
    }

    @Test
    fun `in shadow a slate is reported and nothing is marked`() {
        val (clock, found) = learning(LearnedBreaks.SHADOW)
        clock.plain(whale(100), whaleBase)
        clock.plain(whale(104), whaleBase)
        clock.hear(101 to silent, 102 to silent, 103 to silent, 104 to silent, 105 to loud)
        assertEquals(
            listOf(
                LearnedBreak(SLATE, 24_000, ended = false, marked = false),
                LearnedBreak(SLATE, 24_000, ended = true, marked = false),
            ),
            found,
        )
        assertFalse(clock.plain(whale(102), whaleBase).contains("SOFA-AD"))
        clock.served(whaleSegment(104))
        assertEquals(AdBreakState(), clock.at(aheadMs = 0))
    }

    @Test
    fun `a shorter silence is only reported, a segment heard out of order still joins, and off hears nothing`() {
        val (clock, found) = learning(LearnedBreaks.ON)
        clock.plain(whale(100), whaleBase)
        clock.hear(100 to loud, 102 to silent, 101 to silent, 103 to loud)
        assertEquals(listOf(LearnedBreak(SHORT_SLATE, 12_000, ended = true, marked = false)), found)
        assertFalse(clock.plain(whale(100), whaleBase).contains("SOFA-AD"))

        val (off, none) = learning(LearnedBreaks.OFF)
        off.plain(whale(100), whaleBase)
        off.plain(whale(104), whaleBase)
        off.hear(*(100..107).map { it to silent }.toTypedArray())
        assertEquals(emptyList(), none)
        assertFalse(off.listening)
    }

    @Test
    fun `a playlist that carries a marker is never learned from`() {
        val (clock, found) = learning(LearnedBreaks.ON)
        clock.plain(whale(100, 100 to "#EXT-X-CUE-OUT:DURATION=12"), whaleBase)
        clock.plain(whale(104, 106 to "#EXT-X-CUE-IN"), whaleBase)
        clock.hear(*(100..107).map { it to silent }.toTypedArray())
        assertEquals(emptyList(), found)
    }

    @Test
    fun `the census counts the media played once, the marked breaks and the slates`() {
        val marked = AdClock()
        marked.plain(whale(100, 101 to "#EXT-X-CUE-OUT:DURATION=6", 102 to "#EXT-X-CUE-IN"), whaleBase)
        (100..103).forEach { marked.served(whaleSegment(it)) }
        marked.served(whaleSegment(103))
        assertEquals(AdCensus(playedMs = 24_000, marked = true, breaks = 1, learned = 0), marked.census())

        val (learned, _) = learning(LearnedBreaks.SHADOW)
        learned.plain(whale(100), whaleBase)
        learned.plain(whale(104), whaleBase)
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
    private fun learning(
        mode: LearnedBreaks,
        library: BreakLibrary,
        channel: String,
    ): Pair<AdClock, MutableList<LearnedBreak>> {
        val found = mutableListOf<LearnedBreak>()
        return AdClock(learning = { mode }, library = library, channelId = channel, providerId = "whale", learned = {
            found +=
                it
        }) to
            found
    }

    /** A marked break from 6 to 36 s, closed by its cue-in, heard on [channel]. */
    private fun learnBreak(library: BreakLibrary, channel: String) {
        val (marked, _) = learning(LearnedBreaks.SHADOW, library, channel)
        marked.plain(whale(100, 101 to "#EXT-X-CUE-OUT:DURATION=30"), whaleBase)
        marked.plain(whale(104, 106 to "#EXT-X-CUE-IN"), whaleBase)
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
        shadow.plain(whale(200), whaleBase)
        shadow.plain(whale(204), whaleBase)
        shadow.listen(200, 200..203, adFromMs = 6_000, adUntilMs = 36_000)
        assertEquals(listOf(LIBRARY), found.map { it.rule })
        assertFalse(found.single().marked, "an entry marked on one channel one day is not yet trusted to blank")
        assertFalse(shadow.plain(whale(204), whaleBase).contains("SOFA-AD"))
        shadow.listen(200, 204..207, adFromMs = 6_000, adUntilMs = 36_000)
        val ended = found.last()
        assertTrue(ended.ended)
        // From 3 s into the ad, where the entry starts, to 3 s before its end.
        assertEquals(24_000.0, ended.lengthMs.toDouble(), 300.0)
        assertEquals(AdCensus(playedMs = 0, marked = false, breaks = 0, learned = 1), shadow.census())
    }

    @Test
    fun `a session that joins in a break matches on its first segment, one that has listened a while needs more`() {
        val library = BreakLibrary(null, java.time.Clock.systemUTC())
        learnBreak(library, "c1")

        // The first segment holds the entry's start (3 s into the ad), and 48 points are enough in the first seconds.
        val (joined, found) = learning(LearnedBreaks.SHADOW, library, "c2")
        joined.plain(whale(200), whaleBase)
        joined.listen(200, 200..200, adFromMs = -3_000, adUntilMs = 36_000)
        assertEquals(listOf(LIBRARY), found.map { it.rule })

        val (settled, missed) = learning(LearnedBreaks.SHADOW, library, "c3")
        settled.plain(whale(300), whaleBase)
        settled.plain(whale(310), whaleBase)
        settled.listen(300, 300..305, adFromMs = 100_000, adUntilMs = 100_001)
        settled.listen(300, 306..306, adFromMs = 36_000 - 3_000, adUntilMs = 72_000)
        assertTrue(missed.isEmpty(), missed.toString())
    }

    @Test
    fun `an entry marked on two channels is marked for the player, while the canary is good`() {
        val library = BreakLibrary(null, java.time.Clock.systemUTC())
        learnBreak(library, "c1")
        learnBreak(library, "c2")
        assertEquals(1, library.stats().blanking)
        val (on, found) = learning(LearnedBreaks.ON, library, "c3")
        on.plain(whale(200), whaleBase)
        on.plain(whale(204), whaleBase)
        on.listen(200, 200..203, adFromMs = 6_000, adUntilMs = 36_000)
        assertTrue(found.single().marked, found.toString())
        // The match starts just past 9 s, the middle of segment 201, so the marks start with 202:
        // a little ad shows, never show.
        val marks = on.plain(whale(200), whaleBase)
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
        marked.plain(whale(300, 301 to "#EXT-X-CUE-OUT:DURATION=30"), whaleBase)
        marked.plain(whale(304, 306 to "#EXT-X-CUE-IN"), whaleBase)
        marked.plain(whale(308), whaleBase)
        marked.plain(whale(312), whaleBase)
        marked.listen(300, 300..307, adFromMs = 6_000, adUntilMs = 36_000)
        assertEquals(2, entry.hits)
        assertFalse(entry.blocked)
        // The same audio again at 60 s, long after the break ended: show audio, as far as the markers say.
        marked.listen(300, 308..315, adFromMs = 60_000, adUntilMs = 90_000)
        assertTrue(entry.blocked)
        assertTrue(library.frozen())
        assertEquals(listOf(CANARY), found.map { it.rule })
    }

    /** A live window of [count] six-second segments from [first], as most free services serve about a minute. */
    private fun window(first: Int, count: Int, extra: String = ""): String =
        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n$extra" +
            (first until first + count).joinToString("") { "#EXTINF:6,\nseg-$it.ts\n" }

    @Test
    fun `a minute-long window asks the player to hold 36 seconds back, under the target line`() {
        assertEquals(
            window(100, 10).replace(
                "#EXT-X-TARGETDURATION:6\n",
                "#EXT-X-TARGETDURATION:6\n#EXT-X-SERVER-CONTROL:HOLD-BACK=36\n",
            ),
            AdClock().plain(window(100, 10), whaleBase),
        )
    }

    @Test
    fun `a window too short to hold the player back further than three target durations goes as it was`() {
        // Pluto serves five segments; 30 s leaves 12 s behind 18 s, no more than the default.
        assertEquals(window(100, 5), AdClock().plain(window(100, 5), whaleBase))
        assertEquals(window(100, 3), AdClock().plain(window(100, 3), whaleBase))
    }

    @Test
    fun `a playlist that ended or sets its own server control keeps its own`() {
        val ended = window(100, 10) + "#EXT-X-ENDLIST\n"
        assertEquals(ended, AdClock().plain(ended, whaleBase))
        val own = window(100, 10, "#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES\n")
        assertEquals(own, AdClock().plain(own, whaleBase))
    }

    @Test
    fun `the hold-back keeps its first value while a window keeps growing`() {
        val clock = AdClock()
        assertTrue(clock.plain(window(100, 7), whaleBase).contains("HOLD-BACK=24\n"))
        assertTrue(clock.plain(window(100, 8), whaleBase).contains("HOLD-BACK=24\n"))
        // The player's live target must not move while it watches, however much room the window gains.
        assertTrue(clock.plain(window(100, 9), whaleBase).contains("HOLD-BACK=24\n"))
    }

    @Test
    fun `the hold-back shrinks with a window that no longer fits it and then stays put`() {
        val clock = AdClock()
        assertTrue(clock.plain(window(100, 10), whaleBase).contains("HOLD-BACK=36\n"))
        // 54 s still leaves 18 s behind 36.
        assertTrue(clock.plain(window(101, 9), whaleBase).contains("HOLD-BACK=36\n"))
        // 48 s leaves room for 30 only.
        assertTrue(clock.plain(window(102, 8), whaleBase).contains("HOLD-BACK=30\n"))
        assertTrue(clock.plain(window(103, 10), whaleBase).contains("HOLD-BACK=30\n"))
    }
}
