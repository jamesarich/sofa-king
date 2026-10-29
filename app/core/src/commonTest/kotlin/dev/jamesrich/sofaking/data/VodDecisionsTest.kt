package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VodDecisionsTest {
    private fun stream(mode: String, offsetMs: Long = 0) = PlaybackStream(
        channelId = "",
        sessionId = "v1",
        url = "/media/vod/v1/1/k/index.m3u8",
        mimeType = "application/vnd.apple.mpegurl",
        epoch = 1,
        intent = PlaybackIntent.WATCH,
        seekable = true,
        itemId = "m1",
        mode = mode,
        offsetMs = offsetMs,
    )

    @Test
    fun `a file played as it is seeks anywhere in it`() {
        val direct = stream(Wire.VodMode.DIRECT)
        assertEquals(
            VodSeekPlan.InStream(3_000_000),
            vodSeekPlan(direct, 3_000_000, writtenMs = null, itemDurationMs = 6_000_000),
        )
    }

    @Test
    fun `a converted stream seeks inside what is written and restarts outside it`() {
        val hls = stream(Wire.VodMode.TRANSCODE, offsetMs = 600_000)
        assertEquals(
            VodSeekPlan.InStream(60_000),
            vodSeekPlan(hls, 660_000, writtenMs = 120_000, itemDurationMs = 6_000_000),
        )
        assertEquals(
            VodSeekPlan.Restart(500_000),
            vodSeekPlan(hls, 500_000, writtenMs = 120_000, itemDurationMs = 6_000_000),
            "before the stream",
        )
        assertEquals(
            VodSeekPlan.Restart(718_000),
            vodSeekPlan(hls, 718_000, writtenMs = 120_000, itemDurationMs = 6_000_000),
            "at the written edge",
        )
        assertEquals(
            VodSeekPlan.Restart(2_000_000),
            vodSeekPlan(hls, 2_000_000, writtenMs = null, itemDurationMs = null),
            "nothing written yet",
        )
    }

    @Test
    fun `a seek stays short of the end`() {
        val direct = stream(Wire.VodMode.DIRECT)
        assertEquals(VodSeekPlan.InStream(6_000_000 - END_GUARD_MS), vodSeekPlan(direct, 9_000_000, null, 6_000_000))
        assertEquals(VodSeekPlan.InStream(0), vodSeekPlan(direct, -5_000, null, 6_000_000))
    }

    @Test
    fun `a converted seek lands just inside the edge margin and restarts one millisecond past it`() {
        val hls = stream(Wire.VodMode.TRANSCODE)
        val edge = 120_000 - EDGE_MARGIN_MS
        assertEquals(VodSeekPlan.InStream(edge), vodSeekPlan(hls, edge, writtenMs = 120_000, itemDurationMs = null))
        assertEquals(
            VodSeekPlan.Restart(edge + 1),
            vodSeekPlan(hls, edge + 1, writtenMs = 120_000, itemDurationMs = null),
        )
    }

    @Test
    fun `a converted seek past the end guard restarts at the guarded end`() {
        val hls = stream(Wire.VodMode.TRANSCODE)
        assertEquals(
            VodSeekPlan.Restart(6_000_000 - END_GUARD_MS),
            vodSeekPlan(hls, 7_000_000, writtenMs = 120_000, itemDurationMs = 6_000_000),
        )
    }

    @Test
    fun `a converted seek before the item start restarts at zero`() {
        val later = stream(Wire.VodMode.TRANSCODE, offsetMs = 600_000)
        assertEquals(VodSeekPlan.Restart(0), vodSeekPlan(later, -1_000, writtenMs = 120_000, itemDurationMs = null))
    }

    @Test
    fun `an item shorter than the end guard seeks to its start`() {
        val direct = stream(Wire.VodMode.DIRECT)
        assertEquals(VodSeekPlan.InStream(0), vodSeekPlan(direct, 1_500, null, 1_000))
    }

    @Test
    fun `playback running into a break skips it but a seek into one stays`() {
        val breaks = listOf(VodMarker(60_000, 180_000, VodMarkerKind.BREAK))
        assertEquals(180_000, breakToSkip(breaks, 59_800, 60_050))
        assertNull(breakToSkip(breaks, 30_000, 90_000), "a seek across the start")
        assertNull(breakToSkip(breaks, 90_000, 90_250), "already inside")
        assertNull(breakToSkip(breaks, 60_050, 59_800), "moving back")
    }

    @Test
    fun `the intro is found from its first moment to its end and a break is no intro`() {
        val intro = VodMarker(600, 53_100, VodMarkerKind.INTRO)
        val markers = listOf(intro, VodMarker(60_000, 180_000, VodMarkerKind.BREAK))
        assertNull(introAt(markers, 599))
        assertEquals(intro, introAt(markers, 600))
        assertEquals(intro, introAt(markers, 53_099))
        assertNull(introAt(markers, 53_100))
        assertNull(introAt(markers, 90_000))
        assertNull(breakEndAt(listOf(intro), 10_000), "Skip break never skips an intro")
    }

    @Test
    fun `an intro or the credits is met as its own skip mode says and off is never offered`() {
        val intro = VodMarker(600, 53_100, VodMarkerKind.INTRO)
        val credits = VodMarker(1_200_000, 1_300_000, VodMarkerKind.CREDITS)
        val markers = listOf(intro, credits)
        val skips = Skips(intros = SkipMode.AUTO, credits = SkipMode.BUTTON)
        assertEquals(SegmentSkip(intro, SkipMode.AUTO), segmentToSkip(markers, 10_000, skips))
        assertEquals(SegmentSkip(credits, SkipMode.BUTTON), segmentToSkip(markers, 1_250_000, skips))
        assertNull(segmentToSkip(markers, 600_000, skips))
        val off = Skips(intros = SkipMode.OFF, credits = SkipMode.OFF)
        assertNull(segmentToSkip(markers, 10_000, off))
        assertNull(segmentToSkip(markers, 1_250_000, off))
        assertEquals(
            SegmentSkip(credits, SkipMode.AUTO),
            segmentToSkip(markers, 1_250_000, Skips(intros = SkipMode.OFF, credits = SkipMode.AUTO)),
        )
    }

    @Test
    fun `a break is skippable from its start to its end`() {
        val breaks = listOf(VodMarker(60_000, 180_000, VodMarkerKind.BREAK))
        assertNull(breakEndAt(breaks, 59_999))
        assertEquals(180_000, breakEndAt(breaks, 60_000))
        assertEquals(180_000, breakEndAt(breaks, 179_999))
        assertNull(breakEndAt(breaks, 180_000))
    }

    @Test
    fun `credits that run to the end of the item mean the next episode`() {
        assertTrue(creditsRunToEnd(1_500_000, 1_500_000))
        assertTrue(creditsRunToEnd(1_499_000, 1_500_000))
        assertFalse(creditsRunToEnd(1_498_999, 1_500_000))
        assertFalse(creditsRunToEnd(1_500_000, null))
        assertFalse(creditsRunToEnd(1_500_000, 0))
    }

    @Test
    fun `an end within the tolerance or with no known length finishes the item`() {
        val guard = EarlyEndGuard()
        assertEquals(EarlyEnd.FINISHED, guard.onEnd(600_000 - EARLY_END_MS, 600_000))
        assertEquals(EarlyEnd.FINISHED, guard.onEnd(1_000, null))
    }

    @Test
    fun `an end short of the item reopens twice and then fails`() {
        val guard = EarlyEndGuard()
        assertEquals(EarlyEnd.REOPEN, guard.onEnd(120_000, 600_000))
        assertEquals(EarlyEnd.REOPEN, guard.onEnd(120_000, 600_000))
        assertEquals(EarlyEnd.FAIL, guard.onEnd(120_000, 600_000))
    }

    @Test
    fun `an end short of the item after playback moved on by 30 seconds reopens again`() {
        val guard = EarlyEndGuard()
        repeat(2) { guard.onEnd(120_000, 600_000) }
        assertEquals(EarlyEnd.REOPEN, guard.onEnd(150_000, 600_000))
    }

    @Test
    fun `an end short of the item less than 30 seconds on still counts against the reopens`() {
        val guard = EarlyEndGuard()
        repeat(2) { guard.onEnd(120_000, 600_000) }
        assertEquals(EarlyEnd.FAIL, guard.onEnd(149_999, 600_000))
    }

    @Test
    fun `a failed stream reopens whatever the item length`() {
        val guard = EarlyEndGuard()
        assertEquals(EarlyEnd.REOPEN, guard.onDrop(1_000))
    }

    @Test
    fun `a busy converter is asked again every 5 seconds for 20 seconds`() {
        assertEquals(CONVERTER_RETRY_MS, converterRetryDelayMs(0))
        assertEquals(CONVERTER_RETRY_MS, converterRetryDelayMs(CONVERTER_WAIT_MS - CONVERTER_RETRY_MS))
        assertNull(converterRetryDelayMs(CONVERTER_WAIT_MS))
    }

    @Test
    fun `the auto skip countdown is 5 seconds`() {
        assertEquals(5_000L, AUTO_SKIP_MS)
    }
}
