package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveResumeTest {
    private val pausedAt = 1_000_000L

    @Test
    fun `never paused means no jump to live`() {
        assertFalse(shouldResumeAtLive(pausedAtMs = null, nowMs = pausedAt + 3_600_000L))
    }

    @Test
    fun `a short pause resumes where it stopped`() {
        assertFalse(shouldResumeAtLive(pausedAtMs = pausedAt, nowMs = pausedAt + 5_000L))
    }

    @Test
    fun `exactly the threshold still resumes where it stopped`() {
        assertFalse(shouldResumeAtLive(pausedAtMs = pausedAt, nowMs = pausedAt + LIVE_RESUME_AFTER_MS))
    }

    @Test
    fun `a pause past the threshold resumes at live`() {
        assertTrue(shouldResumeAtLive(pausedAtMs = pausedAt, nowMs = pausedAt + LIVE_RESUME_AFTER_MS + 1))
    }

    @Test
    fun `the pause pill counts the seconds behind live and says when resuming jumps to live`() {
        assertEquals(PauseStatus(12, resumesLive = false), pauseStatus(pausedAtMs = 1_000L, nowMs = 13_400L))
        assertEquals(PauseStatus(31, resumesLive = true), pauseStatus(pausedAtMs = 0L, nowMs = 31_000L))
        assertEquals(PauseStatus(0, resumesLive = false), pauseStatus(pausedAtMs = 5_000L, nowMs = 4_000L))
    }
}
