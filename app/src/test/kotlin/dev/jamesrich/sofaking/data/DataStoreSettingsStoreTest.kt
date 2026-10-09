package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

    /** One DataStore per test: a second instance over the same file would not see the first one's writes. */
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.root, "test.preferences_pb")
        }
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newStore(): DataStoreSettingsStore = DataStoreSettingsStore(dataStore, defaults)

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
    fun `caption advance defaults to three seconds and is clamped to eight`() = runTest {
        val store = newStore()
        assertEquals(3_000, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(5_500)
        assertEquals(5_500, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(42_000)
        assertEquals(8_000, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(-3_000)
        assertEquals(0, store.settings.first().captionAdvanceMs)
    }

    @Test
    fun `per-channel caption advance overrides the default only for that channel`() = runTest {
        val store = newStore()
        store.setCaptionAdvanceMs(5_000)
        store.setChannelCaptionAdvance("5", 1_000)
        store.setChannelCaptionAdvance("8", 6_500)
        store.setChannelCaptionAdvance("5", 2_500)

        val settings = store.settings.first()
        assertEquals(2_500, settings.captionAdvanceFor("5"))
        assertEquals(6_500, settings.captionAdvanceFor("8"))
        assertEquals("unadjusted channels use the default", 5_000, settings.captionAdvanceFor("108"))
    }

    @Test
    fun `a legacy whole-seconds default is read as seconds`() = runTest {
        dataStore.edit { it[intPreferencesKey("caption_advance_seconds")] = 5 }

        assertEquals(5_000, newStore().settings.first().captionAdvanceMs)
    }

    @Test
    fun `legacy whole-seconds values are clamped to the old six-second bound before scaling`() = runTest {
        dataStore.edit { it[intPreferencesKey("caption_advance_seconds")] = 42 }

        assertEquals(6_000, newStore().settings.first().captionAdvanceMs)
    }

    @Test
    fun `legacy per-channel seconds are read as seconds and keep their channel`() = runTest {
        dataStore.edit { it[stringSetPreferencesKey("channel_caption_advance")] = setOf("5\t2", "8\t6") }

        val settings = newStore().settings.first()
        assertEquals(2_000, settings.captionAdvanceFor("5"))
        assertEquals(6_000, settings.captionAdvanceFor("8"))
        assertEquals(3_000, settings.captionAdvanceFor("108"))
    }

    @Test
    fun `a millisecond value wins over a legacy seconds value`() = runTest {
        dataStore.edit { it[intPreferencesKey("caption_advance_seconds")] = 5 }
        val store = newStore()
        store.setCaptionAdvanceMs(1_500)

        assertEquals(1_500, store.settings.first().captionAdvanceMs)
    }

    @Test
    fun `writing one channel keeps every legacy channel value`() = runTest {
        dataStore.edit { it[stringSetPreferencesKey("channel_caption_advance")] = setOf("5\t2") }
        val store = newStore()

        store.setChannelCaptionAdvance("8", 6_500)

        val settings = store.settings.first()
        assertEquals(2_000, settings.captionAdvanceFor("5"))
        assertEquals(6_500, settings.captionAdvanceFor("8"))
    }

    @Test
    fun `smooth captions are on by default and can be turned off`() = runTest {
        val store = newStore()
        assertTrue(store.settings.first().smoothCaptions)

        store.setSmoothCaptions(false)
        assertFalse(store.settings.first().smoothCaptions)
    }

    @Test
    fun `caption style is system by default and persists the large style`() = runTest {
        val store = newStore()
        assertEquals(CaptionStyle.SYSTEM, store.settings.first().captionStyle)

        store.setCaptionStyle(CaptionStyle.LARGE_HIGH_CONTRAST)
        assertEquals(CaptionStyle.LARGE_HIGH_CONTRAST, store.settings.first().captionStyle)
    }

    @Test
    fun `an unrecognised stored caption style falls back to system`() = runTest {
        dataStore.edit { it[stringPreferencesKey("caption_style")] = "NO_SUCH_STYLE" }

        assertEquals(CaptionStyle.SYSTEM, newStore().settings.first().captionStyle)
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
