package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.ui.formatPlayTime
import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.PlaybackIntent
import dev.jamesrich.sofaking.data.PlaybackStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class VodPlaybackTest {
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
        assertEquals(VodSeekPlan.InStream(3_000_000), vodSeekPlan(direct, 3_000_000, writtenMs = null, itemDurationMs = 6_000_000))
    }

    @Test
    fun `a converted stream seeks inside what is written and restarts outside it`() {
        val hls = stream(Wire.VodMode.TRANSCODE, offsetMs = 600_000)
        assertEquals(VodSeekPlan.InStream(60_000), vodSeekPlan(hls, 660_000, writtenMs = 120_000, itemDurationMs = 6_000_000))
        assertEquals(VodSeekPlan.Restart(500_000), vodSeekPlan(hls, 500_000, writtenMs = 120_000, itemDurationMs = 6_000_000), "before the stream")
        assertEquals(VodSeekPlan.Restart(718_000), vodSeekPlan(hls, 718_000, writtenMs = 120_000, itemDurationMs = 6_000_000), "at the written edge")
        assertEquals(VodSeekPlan.Restart(2_000_000), vodSeekPlan(hls, 2_000_000, writtenMs = null, itemDurationMs = null), "nothing written yet")
    }

    @Test
    fun `a seek stays short of the end`() {
        val direct = stream(Wire.VodMode.DIRECT)
        assertEquals(VodSeekPlan.InStream(6_000_000 - END_GUARD_MS), vodSeekPlan(direct, 9_000_000, null, 6_000_000))
        assertEquals(VodSeekPlan.InStream(0), vodSeekPlan(direct, -5_000, null, 6_000_000))
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
    fun `presses build a seek that applies once they rest`() {
        val scrub = VodScrub()
        assertEquals(130_000, scrub.press(1, positionMs = 100_000, durationMs = 1_000_000, repeatCount = 0, nowMs = 0))
        assertEquals(120_000, scrub.press(-1, positionMs = 100_000, durationMs = 1_000_000, repeatCount = 0, nowMs = 100))
        assertNull(scrub.settled(100 + VodScrub.SCRUB_SETTLE_MS - 1))
        assertEquals(120_000, scrub.settled(100 + VodScrub.SCRUB_SETTLE_MS))
        assertNull(scrub.targetMs)
    }

    @Test
    fun `a held key moves further and stops at the ends`() {
        val scrub = VodScrub()
        assertEquals(160_000, scrub.press(1, 100_000, 1_000_000, repeatCount = 8, nowMs = 0))
        assertEquals(460_000, scrub.press(1, 100_000, 1_000_000, repeatCount = 24, nowMs = 10))
        assertEquals(760_000, scrub.press(1, 100_000, 1_000_000, repeatCount = 30, nowMs = 20))
        assertEquals(1_000_000, scrub.press(1, 100_000, 1_000_000, repeatCount = 31, nowMs = 30))
        scrub.cancel()
        assertEquals(0, scrub.press(-1, 5_000, 1_000_000, repeatCount = 0, nowMs = 30))
        assertEquals(0, scrub.commit())
        assertNull(scrub.commit())
    }

    @Test
    fun `the remote's keys map to seeking playing and the bar`() {
        assertEquals(VodAction.BACK, Key.DirectionLeft.vodAction())
        assertEquals(VodAction.FORWARD, Key.MediaFastForward.vodAction())
        assertEquals(VodAction.PLAY_PAUSE, Key.DirectionCenter.vodAction())
        assertEquals(VodAction.SHOW_INFO, Key.DirectionDown.vodAction())
        assertNull(Key.Back.vodAction(), "Back is left to the back handler")
    }

    @Test
    fun `times read as minutes or hours`() {
        assertEquals("0:00", formatPlayTime(-1))
        assertEquals("4:05", formatPlayTime(245_999))
        assertEquals("1:02:03", formatPlayTime(3_723_000))
    }

    @Test
    fun `the end is what is left of the item from now`() {
        val now = Instant.parse("2026-10-19T20:00:00Z")
        assertEquals(Instant.parse("2026-10-19T20:52:30Z"), vodEndsAt(now, positionMs = 1_800_000L, durationMs = 4_950_000L))
        assertEquals(now, vodEndsAt(now, positionMs = 9_000L, durationMs = 5_000L))
        assertNull(vodEndsAt(now, positionMs = 0, durationMs = null))
    }
}
