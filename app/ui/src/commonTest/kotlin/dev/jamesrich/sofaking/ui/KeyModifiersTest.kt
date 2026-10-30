package dev.jamesrich.sofaking.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyModifiersTest {
    @Test
    fun `a letter shortcut with control meta or alt held is left to the system`() {
        for (key in listOf(Key.C, Key.K, Key.M, Key.F)) {
            assertTrue(blocksLetterShortcut(key, ctrl = true, meta = false, alt = false))
            assertTrue(blocksLetterShortcut(key, ctrl = false, meta = true, alt = false))
            assertTrue(blocksLetterShortcut(key, ctrl = false, meta = false, alt = true))
        }
    }

    @Test
    fun `a plain letter shortcut still runs`() {
        assertFalse(blocksLetterShortcut(Key.C, ctrl = false, meta = false, alt = false))
    }

    @Test
    fun `a remote key is never blocked by a modifier`() {
        assertFalse(blocksLetterShortcut(Key.MediaPlayPause, ctrl = true, meta = true, alt = true))
        assertFalse(blocksLetterShortcut(Key.Captions, ctrl = true, meta = false, alt = false))
    }
}
