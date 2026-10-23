package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepTimerTest {
    private val minute = 60_000L
    private val sleep = SleepTimer()

    @Test
    fun `each press steps through the minutes and then the end of the program and off`() {
        val programEnd = 40 * minute
        sleep.step(0, programEnd)
        assertEquals(SleepChoice.MINUTES_15, sleep.choice)
        assertEquals(15 * minute, sleep.stopAtMs)
        sleep.step(minute, programEnd)
        assertEquals(31 * minute, sleep.stopAtMs, "each step counts from the press")
        sleep.step(minute, programEnd)
        sleep.step(minute, programEnd)
        assertEquals(SleepChoice.MINUTES_90, sleep.choice)
        sleep.step(minute, programEnd)
        assertEquals(SleepChoice.END, sleep.choice)
        assertEquals(programEnd, sleep.stopAtMs)
        assertFalse(sleep.atEndOfItem)
        sleep.step(minute, programEnd)
        assertEquals(SleepChoice.OFF, sleep.choice)
        assertNull(sleep.stopAtMs)
    }

    @Test
    fun `with no program on the step after 90 minutes turns the timer off`() {
        repeat(4) { sleep.step(0) }
        sleep.step(0)
        assertEquals(SleepChoice.OFF, sleep.choice)
        repeat(4) { sleep.step(0, programEndMs = 0) }
        sleep.step(minute, programEndMs = 0)
        assertEquals(SleepChoice.OFF, sleep.choice, "a program already over is no end to wait for")
    }

    @Test
    fun `an item ends playback as it ends`() {
        repeat(5) { sleep.step(0, endOfItem = true) }
        assertEquals(SleepChoice.END, sleep.choice)
        assertTrue(sleep.atEndOfItem)
        assertNull(sleep.stopAtMs)
        assertFalse(sleep.due(Long.MAX_VALUE))
    }

    @Test
    fun `the timer is due once its time comes and counts whole minutes up to it`() {
        sleep.step(0)
        assertEquals(15, sleep.minutesLeft(0))
        assertEquals(1, sleep.minutesLeft(14 * minute + 1))
        assertFalse(sleep.due(15 * minute - 1))
        assertTrue(sleep.due(15 * minute))
        sleep.cancel()
        assertFalse(sleep.due(Long.MAX_VALUE))
        assertNull(sleep.minutesLeft(0))
    }

    @Test
    fun `Up next asks Still watching after two items played by themselves`() {
        val run = AutoplayRun()
        assertFalse(run.askStillWatching(lastInputMs = 0))
        run.autoplayed(1_000)
        assertFalse(run.askStillWatching(lastInputMs = 0))
        run.autoplayed(2_000)
        assertTrue(run.askStillWatching(lastInputMs = 0))
    }

    @Test
    fun `a key or a tap since the last autoplay starts the count again`() {
        val run = AutoplayRun()
        run.autoplayed(1_000)
        run.autoplayed(2_000)
        assertFalse(run.askStillWatching(lastInputMs = 2_500))
        run.autoplayed(3_000)
        assertFalse(run.askStillWatching(lastInputMs = 2_500))
        run.autoplayed(4_000)
        assertTrue(run.askStillWatching(lastInputMs = 2_500))
    }
}
