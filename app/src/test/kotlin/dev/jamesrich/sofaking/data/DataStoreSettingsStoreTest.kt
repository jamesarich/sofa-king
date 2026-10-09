package dev.jamesrich.sofaking.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope

    private val defaults = AppSettings(serverUrl = "http://default:9191", profile = "livingroom")

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newStore(): DataStoreSettingsStore {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.root, "test.preferences_pb")
        }
        return DataStoreSettingsStore(dataStore, defaults)
    }

    @Test
    fun `empty store reports the defaults`() = runTest {
        val settings = newStore().settings.first()
        assertEquals(defaults, settings)
    }

    @Test
    fun `setServer trims whitespace and strips a trailing slash`() = runTest {
        val store = newStore()
        store.setServer("  http://dispatcharr.lan:9191/  ", " lounge ")

        val settings = store.settings.first()
        assertEquals("http://dispatcharr.lan:9191", settings.serverUrl)
        assertEquals("lounge", settings.profile)
    }

    @Test
    fun `toggleFavorite adds then removes a guide id`() = runTest {
        val store = newStore()

        store.toggleFavorite("5")
        assertEquals(setOf("5"), store.settings.first().favorites)

        store.toggleFavorite("5")
        assertEquals(emptySet<String>(), store.settings.first().favorites)
    }

    @Test
    fun `setLastChannel is persisted`() = runTest {
        val store = newStore()
        assertEquals(null, store.settings.first().lastChannel)

        store.setLastChannel("12")
        assertEquals("12", store.settings.first().lastChannel)
    }

    @Test
    fun `captions are off until the user turns them on`() = runTest {
        assertFalse(newStore().settings.first().captionsEnabled)
    }

    @Test
    fun `setCaptionsEnabled is persisted in both directions`() = runTest {
        val store = newStore()

        store.setCaptionsEnabled(true)
        assertTrue(store.settings.first().captionsEnabled)

        store.setCaptionsEnabled(false)
        assertFalse(store.settings.first().captionsEnabled)
    }

    @Test
    fun `caption advance defaults to three seconds and is clamped`() = runTest {
        val store = newStore()
        assertEquals(3, store.settings.first().captionAdvanceSeconds)

        store.setCaptionAdvanceSeconds(5)
        assertEquals(5, store.settings.first().captionAdvanceSeconds)

        store.setCaptionAdvanceSeconds(42)
        assertEquals(6, store.settings.first().captionAdvanceSeconds)

        store.setCaptionAdvanceSeconds(-3)
        assertEquals(0, store.settings.first().captionAdvanceSeconds)
    }

    @Test
    fun `settings flow emits each update`() = runTest {
        val store = newStore()

        store.setServer("http://a:9191", "one")
        assertEquals("one", store.settings.first { it.profile == "one" }.profile)

        store.setServer("http://b:9191", "two")
        val updated = store.settings.first { it.profile == "two" }
        assertEquals("http://b:9191", updated.serverUrl)
    }
}
