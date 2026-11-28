package dev.jamesrich.sofaking.ui.preview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgramImageTest {
    @Test
    fun `a program on now gives its image's width to the text while the preview shows it`() {
        assertFalse(showsProgramImage(live = true, previewFailed = false))
    }

    @Test
    fun `a program on now shows its image when the preview has no picture`() {
        assertTrue(showsProgramImage(live = true, previewFailed = true))
    }

    @Test
    fun `a program on later shows its image, since the preview is of what is on now`() {
        assertTrue(showsProgramImage(live = false, previewFailed = false))
        assertTrue(showsProgramImage(live = false, previewFailed = true))
    }
}
