package dev.jamesrich.sofaking.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullScreenTest {
    @Test
    fun `F switches full screen only in a player`() {
        assertTrue(Key.F.togglesFullScreen(inPlayer = true))
        assertFalse(Key.F.togglesFullScreen(inPlayer = false))
    }

    @Test
    fun `F11 switches full screen on any page`() {
        assertTrue(Key.F11.togglesFullScreen(inPlayer = true))
        assertTrue(Key.F11.togglesFullScreen(inPlayer = false))
    }

    @Test
    fun `the player keys other than F keep their meaning`() {
        listOf(Key.C, Key.Escape, Key.Enter, Key.DirectionUp, Key.Spacebar).forEach { assertFalse(it.togglesFullScreen(inPlayer = true)) }
    }
}
