package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.ui.player.CaptionSmoother.Decision
import kotlin.test.assertEquals
import kotlin.test.Test

class CaptionSmootherTest {
    private fun decide(shown: String, incoming: String, sinceEmitMs: Long = 100L): Decision =
        CaptionSmoother.decide(shown, incoming, nowMs = 1_000L, lastEmitMs = 1_000L - sinceEmitMs)

    @Test
    fun `a cleared screen is shown at once never held`() {
        assertEquals(Decision.EMIT, decide(shown = "HELLO WO", incoming = ""))
    }

    @Test
    fun `text onto an empty screen is shown at once`() {
        assertEquals(Decision.EMIT, decide(shown = "", incoming = "HELLO"))
    }

    @Test
    fun `an unchanged line is shown at once`() {
        assertEquals(Decision.EMIT, decide(shown = "HELLO", incoming = "HELLO"))
    }

    @Test
    fun `a replaced line that does not extend the shown text is shown at once`() {
        assertEquals(Decision.EMIT, decide(shown = "HELLO WO", incoming = "GOODBYE"))
    }

    @Test
    fun `a line growing mid-word within the hold window is held`() {
        assertEquals(Decision.HOLD, decide(shown = "HELLO", incoming = "HELLO WO", sinceEmitMs = 100L))
    }

    @Test
    fun `a growing line is shown once the hold window has passed`() {
        assertEquals(Decision.EMIT, decide(shown = "HELLO", incoming = "HELLO WO", sinceEmitMs = 250L))
        assertEquals(Decision.EMIT, decide(shown = "HELLO", incoming = "HELLO WO", sinceEmitMs = 400L))
    }

    @Test
    fun `a growing line ending in whitespace is shown at once`() {
        assertEquals(Decision.EMIT, decide(shown = "HELLO", incoming = "HELLO ", sinceEmitMs = 50L))
    }

    @Test
    fun `a growing line ending in punctuation is shown at once`() {
        assertEquals(Decision.EMIT, decide(shown = "HELLO", incoming = "HELLO.", sinceEmitMs = 50L))
    }

    @Test
    fun `a growing line that adds a roll-up line is shown at once`() {
        assertEquals(Decision.EMIT, decide(shown = "FIRST LINE", incoming = "FIRST LINE\nSECOND", sinceEmitMs = 50L))
    }
}
