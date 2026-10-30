package dev.jamesrich.sofaking.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderTest {
    @Test
    fun `touch exploration alone counts as a screen reader`() {
        assertTrue(screenReaderOn(touchExploration = true, spokenFeedbackServices = 0))
    }

    @Test
    fun `a spoken feedback service counts on a TV where touch exploration stays off`() {
        assertTrue(screenReaderOn(touchExploration = false, spokenFeedbackServices = 1))
    }

    @Test
    fun `no touch exploration and no spoken service is no screen reader`() {
        assertFalse(screenReaderOn(touchExploration = false, spokenFeedbackServices = 0))
    }
}
