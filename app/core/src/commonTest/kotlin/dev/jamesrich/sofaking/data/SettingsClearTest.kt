package dev.jamesrich.sofaking.data

import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Clearing a setting that was never set. DataStore's `remove` returns the removed value as its non-null type, so on
 * Wasm, which checks that cast, a missing key threw from the browser's Settings (found by the web key fuzz).
 */
class SettingsClearTest {
    private val store = DataStoreSettingsStore(MemoryDataStore(emptyPreferences()))

    @Test
    fun `clearing settings that were never set leaves them at their defaults`() = runTest {
        store.setStartup(StartupTarget.GUIDE, "7")
        store.setGuideFilter(null)
        store.setSettingsPin(null)
        val settings = store.settings.first()
        assertEquals(AppSettings(startup = StartupTarget.GUIDE), settings)
    }
}
