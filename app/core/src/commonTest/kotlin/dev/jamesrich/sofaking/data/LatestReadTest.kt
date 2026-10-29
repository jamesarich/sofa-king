package dev.jamesrich.sofaking.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LatestReadTest {
    @Test
    fun `two collectors of one snapshot share one read`() {
        var reads = 0
        val latest = LatestRead {
            reads++
            AppSettings()
        }
        val snapshot = mutablePreferencesOf(stringPreferencesKey("k") to "v")

        assertSame(latest.of(snapshot), latest.of(snapshot))
        assertEquals(1, reads)
    }

    @Test
    fun `a new snapshot is read again`() {
        var reads = 0
        val latest = LatestRead {
            reads++
            AppSettings()
        }

        latest.of(mutablePreferencesOf(stringPreferencesKey("k") to "a"))
        latest.of(mutablePreferencesOf(stringPreferencesKey("k") to "b"))
        assertEquals(2, reads)
    }

    @Test
    fun `a collector that starts after a write sees the write`() = runTest {
        val store = DataStoreSettingsStore(MemoryDataStore(emptyPreferences()))
        assertEquals("", store.settings.first().serverUrl)

        store.setServer("http://sofa.test", "t")
        assertEquals("http://sofa.test", store.settings.first().serverUrl)
    }
}
