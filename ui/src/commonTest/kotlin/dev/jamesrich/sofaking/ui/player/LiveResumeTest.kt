package dev.jamesrich.sofaking.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
