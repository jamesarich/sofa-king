package dev.jamesrich.sofaking.ui.guide

import androidx.compose.ui.input.key.Key
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class GuideKeysTest {
    @Test
    fun `Guide and Menu jump to Now`() {
        assertEquals(GuideShortcut.NOW, Key.Guide.guideShortcut())
        assertEquals(GuideShortcut.NOW, Key.Menu.guideShortcut())
    }

    @Test
    fun `navigation keys are not shortcuts`() {
        assertNull(Key.DirectionDown.guideShortcut())
        assertNull(Key.Back.guideShortcut())
        assertNull(Key.DirectionCenter.guideShortcut())
    }

    @Test
    fun `Search opens voice search`() {
        assertEquals(GuideShortcut.SEARCH, Key.Search.guideShortcut())
    }
}
