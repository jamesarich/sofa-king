package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsReadRetryTest {
    /** A store whose first [failures] reads throw, as a file the platform cannot read for a moment does. */
    private class FlakyStore(var failures: Int) : DataStore<Preferences> {
        private val memory = MemoryDataStore(emptyPreferences())

        override val data: Flow<Preferences> = flow {
            if (failures > 0) {
                failures--
                throw IOException("unreadable")
            }
            emitAll(memory.data)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) =
            memory.updateData(transform)
    }

    @Test
    fun `an unreadable store reads as defaults and its settings return once it reads again`() = runTest {
        val store = DataStoreSettingsStore(FlakyStore(failures = 2), readRetry = 5.seconds)
        store.setServer("http://sofa.test", "t")
        val seen = mutableListOf<String>()
        backgroundScope.launch { store.settings.collect { seen += it.serverUrl } }
        runCurrent()
        assertEquals(listOf(""), seen)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(listOf("", ""), seen, "a read that fails again reads as defaults again")

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(listOf("", "", "http://sofa.test"), seen)

        store.setServer("http://sofa.test/other", "t")
        runCurrent()
        assertEquals("http://sofa.test/other", seen.last(), "a later write is seen")
    }
}
