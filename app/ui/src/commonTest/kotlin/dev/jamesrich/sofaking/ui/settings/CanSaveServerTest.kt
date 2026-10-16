package dev.jamesrich.sofaking.ui.settings

import dev.jamesrich.sofaking.data.AppSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanSaveServerTest {
    private val stored = AppSettings(serverUrl = "http://sofa.lan:9193", apiToken = "token")

    @Test
    fun `save is off while the fields match what is stored`() {
        assertFalse(canSaveServer(stored, "http://sofa.lan:9193", "token"))
    }

    @Test
    fun `save is on once either field differs and both are valid`() {
        assertTrue(canSaveServer(stored, "http://sofa.lan:9194", "token"))
        assertTrue(canSaveServer(stored, "http://sofa.lan:9193", "other"))
        assertTrue(canSaveServer(AppSettings(), "http://sofa.lan:9193", ""))
    }

    @Test
    fun `an invalid field keeps save off whatever changed`() {
        assertFalse(canSaveServer(stored, null, "other"))
        assertFalse(canSaveServer(stored, "http://sofa.lan:9194", null))
    }
}
