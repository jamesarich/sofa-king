package dev.jamesrich.sofaking.ui.preview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewCaptionsTest {
    @Test
    fun `captions draw over a playing preview when the setting is on`() {
        assertTrue(showPreviewCaptions(captionsEnabled = true, hasChannel = true, failed = false))
    }

    @Test
    fun `captions stay off with the setting off no channel or a failed preview`() {
        assertFalse(showPreviewCaptions(captionsEnabled = false, hasChannel = true, failed = false))
        assertFalse(showPreviewCaptions(captionsEnabled = true, hasChannel = false, failed = false))
        assertFalse(showPreviewCaptions(captionsEnabled = true, hasChannel = true, failed = true))
    }
}
