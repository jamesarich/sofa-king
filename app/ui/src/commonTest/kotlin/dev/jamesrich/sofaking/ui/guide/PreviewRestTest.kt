package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.PreviewDwell
import dev.jamesrich.sofaking.data.Program
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewRestTest {
    private val channel = Channel("1", 1.0, "Ch 1", null, listOf("Test"))
    private val start = Instant.parse("2026-10-09T10:00:00Z")
    private val cell = GuideCell(Program("1", start, start + 30.minutes, "Show", null), 0, 30)
    private val state = GuideState().apply { focused = FocusedCell(channel, cell) }
    private val opened = mutableListOf<String>()

    private fun TestScope.rests(failed: Boolean = false, dwell: PreviewDwell = PreviewDwell()) = launch {
        state.rest("1", failed, sound = true, dwell, { testScheduler.currentTime }) { opened += it.guideId }
    }

    @Test
    fun `the last three seconds before a channel opens by itself count down on the preview`() = runTest {
        rests()
        advanceTimeBy(6_999)
        runCurrent()
        assertNull(state.previewOpensIn)
        assertFalse(state.previewMuted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, state.previewOpensIn)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, state.previewOpensIn)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, state.previewOpensIn)
        assertTrue(opened.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("1"), opened)
        assertNull(state.previewOpensIn)
    }

    @Test
    fun `a key during the countdown clears it until the rest comes round again`() = runTest {
        val dwell = PreviewDwell()
        val first = rests(dwell = dwell)
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(2, state.previewOpensIn)
        // What a key does: the timers start over and the effect times a new rest.
        first.cancel()
        dwell.onKey(testScheduler.currentTime)
        rests(dwell = dwell)
        runCurrent()
        assertNull(state.previewOpensIn)
        assertTrue(state.previewMuted)
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(3, state.previewOpensIn)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a preview without a picture counts nothing down and does not open`() = runTest {
        rests(failed = true)
        advanceTimeBy(8_000)
        runCurrent()
        assertNull(state.previewOpensIn)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(opened.isEmpty())
    }
}
