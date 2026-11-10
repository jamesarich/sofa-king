package dev.jamesrich.sofaking.ui.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepWarningTest {
    private val minute = 60_000L
    private val sleep = SleepTimer()
    private val warning = SleepWarning(sleep)

    @Test
    fun `the card stays away until a minute is left`() {
        assertNull(sleepWarningSeconds(stopAtMs = null, nowMs = 0, dismissedFor = null))
        assertNull(sleepWarningSeconds(15 * minute, 0, null))
        assertNull(sleepWarningSeconds(minute, -1, null))
        assertEquals(60, sleepWarningSeconds(minute, 0, null))
    }

    @Test
    fun `the seconds round up and the card ends at the stop time`() {
        assertEquals(1, sleepWarningSeconds(minute, minute - 1, null))
        assertEquals(2, sleepWarningSeconds(minute, minute - 1_001, null))
        assertNull(sleepWarningSeconds(minute, minute, null))
        assertNull(sleepWarningSeconds(minute, minute + 5, null))
    }

    @Test
    fun `a dismissed card stays away for that stop time only`() {
        assertNull(sleepWarningSeconds(minute, 0, dismissedFor = minute))
        assertEquals(60, sleepWarningSeconds(minute, 0, dismissedFor = minute + 1))
    }

    @Test
    fun `dismissing leaves the timer running`() {
        sleep.step(0)
        warning.tick(15 * minute - 30_000)
        assertEquals(30, warning.secondsLeft)
        warning.dismiss()
        assertNull(warning.secondsLeft)
        warning.tick(15 * minute - 20_000)
        assertNull(warning.secondsLeft)
        assertEquals(15 * minute, sleep.stopAtMs)
        assertTrue(sleep.due(15 * minute))
    }

    @Test
    fun `extending moves the stop 15 minutes later and the card returns a minute before it`() {
        sleep.step(0)
        warning.tick(15 * minute - 10_000)
        warning.extend()
        assertEquals(30 * minute, sleep.stopAtMs)
        assertNull(warning.secondsLeft)
        assertFalse(sleep.due(15 * minute))
        warning.tick(30 * minute - 59_000)
        assertEquals(59, warning.secondsLeft)
    }

    @Test
    fun `extending a timer that waits for the end of an item changes nothing`() {
        sleep.step(0, endOfItem = true)
        repeat(4) { sleep.step(0, endOfItem = true) }
        sleep.extend(SLEEP_EXTEND_MS)
        assertNull(sleep.stopAtMs)
    }

    @Test
    fun `OK extends on its key-up while the card is up and is taken on both events`() {
        sleep.step(0)
        warning.tick(15 * minute - 5_000)
        assertTrue(warning.onKey(Key.DirectionCenter, KeyEventType.KeyDown, buttonsFocused = false))
        assertEquals(15 * minute, sleep.stopAtMs)
        assertTrue(warning.onKey(Key.DirectionCenter, KeyEventType.KeyUp, buttonsFocused = false))
        assertEquals(30 * minute, sleep.stopAtMs)
    }

    @Test
    fun `Back dismisses a card that shows and leaves Back alone otherwise`() {
        sleep.step(0)
        assertFalse(warning.back())
        warning.tick(15 * minute - 5_000)
        assertTrue(warning.back())
        assertFalse(warning.showing)
        assertFalse(warning.back())
    }

    @Test
    fun `a card held back by another card takes neither OK nor Back`() {
        var held = true
        val behindUpNext = SleepWarning(sleep) { held }
        sleep.step(0)
        behindUpNext.tick(15 * minute - 5_000)
        assertFalse(behindUpNext.showing)
        assertFalse(behindUpNext.onKey(Key.DirectionCenter, KeyEventType.KeyUp, buttonsFocused = false))
        assertFalse(behindUpNext.back())
        held = false
        assertTrue(behindUpNext.back())
    }

    @Test
    fun `other keys, a focused button and a hidden card leave OK alone`() {
        sleep.step(0)
        assertFalse(warning.onKey(Key.DirectionCenter, KeyEventType.KeyUp, buttonsFocused = false))
        warning.tick(15 * minute - 5_000)
        assertFalse(warning.onKey(Key.DirectionDown, KeyEventType.KeyUp, buttonsFocused = false))
        assertFalse(warning.onKey(Key.DirectionCenter, KeyEventType.KeyUp, buttonsFocused = true))
        assertEquals(15 * minute, sleep.stopAtMs)
    }
}
