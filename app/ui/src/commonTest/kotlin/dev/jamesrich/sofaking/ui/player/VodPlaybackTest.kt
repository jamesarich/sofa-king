package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import dev.jamesrich.sofaking.data.SeekSteps
import dev.jamesrich.sofaking.ui.formatPlayTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class VodPlaybackTest {
    @Test
    fun `a press moves by the chosen steps and a held key still speeds up`() {
        val scrub = VodScrub()
        scrub.steps = SeekSteps(backSeconds = 5, forwardSeconds = 90)
        assertEquals(190_000, scrub.press(1, positionMs = 100_000, durationMs = 1_000_000, repeatCount = 0, nowMs = 0))
        assertEquals(185_000, scrub.press(-1, positionMs = 100_000, durationMs = 1_000_000, repeatCount = 0, nowMs = 1))
        assertEquals(245_000, scrub.press(1, positionMs = 100_000, durationMs = 1_000_000, repeatCount = 8, nowMs = 2))
    }

    @Test
    fun `presses build a seek that applies once they rest`() {
        val scrub = VodScrub()
        assertEquals(130_000, scrub.press(1, positionMs = 100_000, durationMs = 1_000_000, repeatCount = 0, nowMs = 0))
        assertEquals(
            120_000,
            scrub.press(-1, positionMs = 100_000, durationMs = 1_000_000, repeatCount = 0, nowMs = 100),
        )
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
        assertEquals(VodAction.PLAY_PAUSE, Key.Spacebar.vodAction())
        assertEquals(VodAction.PLAY_PAUSE, Key.K.vodAction())
        assertEquals(VodAction.SHOW_INFO, Key.DirectionUp.vodAction())
        assertEquals(VodAction.BUTTONS, Key.DirectionDown.vodAction())
        assertEquals(VodAction.CAPTIONS, Key.Captions.vodAction())
        assertEquals(VodAction.CAPTIONS, Key.C.vodAction())
        assertEquals(VodAction.BACK, Key.ButtonL1.vodAction())
        assertEquals(VodAction.FORWARD, Key.ButtonR1.vodAction())
        assertEquals(VodAction.TRACKS, Key.MediaAudioTrack.vodAction())
        assertEquals(VodAction.ZOOM, Key.TvZoomMode.vodAction())
        assertEquals(VodAction.STOP, Key.MediaStop.vodAction())
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
        assertEquals(
            Instant.parse("2026-10-19T20:52:30Z"),
            vodEndsAt(now, positionMs = 1_800_000L, durationMs = 4_950_000L),
        )
        assertEquals(now, vodEndsAt(now, positionMs = 9_000L, durationMs = 5_000L))
        assertNull(vodEndsAt(now, positionMs = 0, durationMs = null))
        assertEquals(
            Instant.parse("2026-10-19T20:26:15Z"),
            vodEndsAt(now, positionMs = 1_800_000L, durationMs = 4_950_000L, speed = 2f),
            "at twice the speed it ends in half the time",
        )
    }
}
