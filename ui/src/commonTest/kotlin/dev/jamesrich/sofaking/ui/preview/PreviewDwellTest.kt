package dev.jamesrich.sofaking.ui.preview

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class PreviewDwellTest {
    /** A clock the test advances by hand. */
    private var now = 1_000L
    private val dwell = PreviewDwell()

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun `a tuned preview is muted unmutes at five seconds and opens full screen at ten`() {
        dwell.onFocus("a", now)
        assertTrue(dwell.isMuted(now))
        assertFalse(dwell.opensFullScreen(now))
        advance(4_999)
        assertTrue(dwell.isMuted(now))
        advance(1)
        assertFalse(dwell.isMuted(now))
        assertFalse(dwell.opensFullScreen(now))
        advance(4_999)
        assertFalse(dwell.opensFullScreen(now))
        advance(1)
        assertTrue(dwell.opensFullScreen(now))
    }

    @Test
    fun `a key press restarts both timers and mutes again`() {
        dwell.onFocus("a", now)
        advance(6_000)
        assertFalse(dwell.isMuted(now))
        dwell.onKey(now)
        assertTrue(dwell.isMuted(now))
        advance(4_000)
        assertFalse(dwell.opensFullScreen(now))
        advance(1_000)
        assertFalse(dwell.isMuted(now))
        advance(5_000)
        assertTrue(dwell.opensFullScreen(now))
    }

    @Test
    fun `moving focus to another channel mutes and starts over`() {
        dwell.onFocus("a", now)
        advance(9_000)
        dwell.onFocus("b", now)
        assertTrue(dwell.isMuted(now))
        advance(1_000)
        assertFalse(dwell.opensFullScreen(now))
        advance(9_000)
        assertTrue(dwell.opensFullScreen(now))
    }

    @Test
    fun `off the grid the preview stays muted and never opens`() {
        dwell.onFocus(null, now)
        advance(60_000)
        assertTrue(dwell.isMuted(now))
        assertFalse(dwell.opensFullScreen(now))
        assertNull(dwell.nextTransitionMs(now))
    }

    @Test
    fun `the channel just left unmutes but does not reopen until focus moves`() {
        val returned = PreviewDwell(suppressedChannelId = "a")
        returned.onFocus("a", now)
        advance(5_000)
        assertFalse(returned.isMuted(now))
        advance(30_000)
        assertFalse(returned.opensFullScreen(now))
        returned.onFocus("b", now)
        assertNull(returned.suppressedChannelId)
        returned.onFocus("a", now)
        advance(10_000)
        assertTrue(returned.opensFullScreen(now))
    }

    @Test
    fun `leaving the grid and coming back keeps the suppression`() {
        val returned = PreviewDwell(suppressedChannelId = "a")
        returned.onFocus("a", now)
        returned.onFocus(null, now)
        returned.onFocus("a", now)
        advance(10_000)
        assertFalse(returned.opensFullScreen(now))
    }

    @Test
    fun `the next transition counts down to unmute then full screen then nothing`() {
        dwell.onFocus("a", now)
        assertEquals(5_000L, dwell.nextTransitionMs(now))
        advance(2_000)
        assertEquals(3_000L, dwell.nextTransitionMs(now))
        advance(3_000)
        assertEquals(5_000L, dwell.nextTransitionMs(now))
        advance(5_000)
        assertNull(dwell.nextTransitionMs(now))
    }

    @Test
    fun `a suppressed channel has nothing pending once unmuted`() {
        val returned = PreviewDwell(suppressedChannelId = "a")
        returned.onFocus("a", now)
        assertEquals(5_000L, returned.nextTransitionMs(now))
        advance(5_000)
        assertNull(returned.nextTransitionMs(now))
    }

    @Test
    fun `without a full screen delay the preview unmutes but never opens`() {
        val off = PreviewDwell(fullScreenAfterMs = null)
        off.onFocus("a", now)
        assertEquals(5_000L, off.nextTransitionMs(now))
        advance(5_000)
        assertFalse(off.isMuted(now))
        assertNull(off.nextTransitionMs(now))
        advance(600_000)
        assertFalse(off.opensFullScreen(now))
        assertNull(off.nextTransitionMs(now))
    }

    @Test
    fun `a longer full screen delay is the next transition after unmuting`() {
        val thirty = PreviewDwell(fullScreenAfterMs = 30_000L)
        thirty.onFocus("a", now)
        advance(5_000)
        assertEquals(25_000L, thirty.nextTransitionMs(now))
        advance(24_999)
        assertFalse(thirty.opensFullScreen(now))
        advance(1)
        assertTrue(thirty.opensFullScreen(now))
    }
}
