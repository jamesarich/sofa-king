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

    private fun newStore(): DataStoreSettingsStore = DataStoreSettingsStore(dataStore)

    @Test
    fun `an empty store has no server and no token`() = runTest {
        val settings = newStore().settings.first()
        assertEquals(AppSettings(), settings)
        assertEquals("", settings.serverUrl)
        assertEquals("", settings.apiToken)
    }

    @Test
    fun `setServer trims whitespace and strips a trailing slash`() = runTest {
        val store = newStore()
        store.setServer("  http://sofa.lan:9193/  ", " abc123 ")

        val settings = store.settings.first()
        assertEquals("http://sofa.lan:9193", settings.serverUrl)
        assertEquals("abc123", settings.apiToken)
    }

    @Test
    fun `a playlist server address from an earlier install is not read and is dropped on save`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("server_url")] = "http://192.168.1.3:9191"
            it[stringPreferencesKey("profile")] = "livingroom"
        }
        val store = newStore()
        assertEquals("", store.settings.first().serverUrl)

        store.setServer("http://192.168.1.3:9193", "")

        val prefs = dataStore.data.first()
        assertEquals(null, prefs[stringPreferencesKey("server_url")])
        assertEquals(null, prefs[stringPreferencesKey("profile")])
        assertEquals("http://192.168.1.3:9193", store.settings.first().serverUrl)
    }

    @Test
    fun `the token is kept out of the settings' string form`() {
        val printed = AppSettings(serverUrl = "http://sofa.lan:9193", apiToken = "s3cret-token").toString()
        assertFalse(printed, printed.contains("s3cret"))
        assertTrue(printed, printed.contains("http://sofa.lan:9193"))
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
    fun `caption advance defaults to zero and is clamped to eight seconds`() = runTest {
        val store = newStore()
        assertEquals(0, store.settings.first().captionAdvanceMs)

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
    fun `the old whole-seconds default is not migrated`() = runTest {
        dataStore.edit { it[intPreferencesKey("caption_advance_seconds")] = 6 }

        assertEquals(0, newStore().settings.first().captionAdvanceMs)
    }

    @Test
    fun `legacy per-channel seconds are read as seconds and keep their channel`() = runTest {
        dataStore.edit { it[stringSetPreferencesKey("channel_caption_advance")] = setOf("5\t2", "8\t6") }

        val settings = newStore().settings.first()
        assertEquals(2_000, settings.captionAdvanceFor("5"))
        assertEquals(6_000, settings.captionAdvanceFor("8"))
        assertEquals(0, settings.captionAdvanceFor("108"))
    }

    @Test
    fun `a set default replaces the zero default`() = runTest {
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

        store.setServer("http://a:9193", "one")
        assertEquals("one", store.settings.first { it.apiToken == "one" }.apiToken)

        store.setServer("http://b:9193", "two")
        val updated = store.settings.first { it.apiToken == "two" }
        assertEquals("http://b:9193", updated.serverUrl)
    }
}
