package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings store over the DataStore file every platform with a file system uses, on every such target. */
class DataStoreSettingsStoreTest {
    private val dir = testDirectory()
    private val file = Path(dir, "settings.preferences_pb")

    /** One DataStore per test: a second instance over the same file would not see the first one's writes. */
    private val dataStore: DataStore<Preferences> by lazy {
        SystemFileSystem.createDirectories(dir)
        preferencesDataStore(file.toString())
    }

    @AfterTest
    fun tearDown() {
        if (SystemFileSystem.exists(dir)) SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(dir, mustExist = false)
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
    fun `the token is kept out of the settings' string form`() {
        val printed = AppSettings(serverUrl = "http://sofa.lan:9193", apiToken = "s3cret-token").toString()
        assertFalse(printed.contains("s3cret"), printed)
        assertTrue(printed.contains("http://sofa.lan:9193"), printed)
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
    fun `setLastChannel keeps the ten most recent channels newest first without repeats`() = runTest {
        val store = newStore()
        assertEquals(emptyList(), store.settings.first().recentChannels)

        store.setLastChannel("1")
        store.setLastChannel("2")
        store.setLastChannel("1")
        assertEquals(listOf("1", "2"), store.settings.first().recentChannels)

        for (id in 3..12) store.setLastChannel(id.toString())
        val recent = store.settings.first().recentChannels
        assertEquals(AppSettings.RECENT_CHANNELS, recent.size)
        assertEquals("12", recent.first())
        assertEquals("12", store.settings.first().lastChannel)
        assertFalse(recent.contains("1") || recent.contains("2"), "the oldest channels drop off: $recent")
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
    fun `caption advance defaults to zero and is clamped to eight seconds either way`() = runTest {
        val store = newStore()
        assertEquals(0, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(5_500)
        assertEquals(5_500, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(42_000)
        assertEquals(8_000, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(-3_000)
        assertEquals(-3_000, store.settings.first().captionAdvanceMs)

        store.setCaptionAdvanceMs(-42_000)
        assertEquals(-8_000, store.settings.first().captionAdvanceMs)

        store.setChannelCaptionAdvance("5", -1_500)
        assertEquals(-1_500, store.settings.first().captionAdvanceFor("5"))
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
        assertEquals(5_000, settings.captionAdvanceFor("108"), "unadjusted channels use the default")
    }

    @Test
    fun `a set default replaces the zero default`() = runTest {
        val store = newStore()
        store.setCaptionAdvanceMs(1_500)

        assertEquals(1_500, store.settings.first().captionAdvanceMs)
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
    fun `auto open defaults to ten seconds and round trips every option`() = runTest {
        val store = newStore()
        assertEquals(AutoOpenDelay.AFTER_10_SECONDS, store.settings.first().autoOpenDelay)

        for (delay in AutoOpenDelay.entries) {
            store.setAutoOpenDelay(delay)
            assertEquals(delay, store.settings.first { it.autoOpenDelay == delay }.autoOpenDelay)
        }
    }

    @Test
    fun `an unrecognised stored auto open delay falls back to ten seconds`() = runTest {
        dataStore.edit { it[stringPreferencesKey("auto_open_delay")] = "NO_SUCH_DELAY" }

        assertEquals(AutoOpenDelay.AFTER_10_SECONDS, newStore().settings.first().autoOpenDelay)
    }

    @Test
    fun `preview sound is on by default and can be turned off`() = runTest {
        val store = newStore()
        assertTrue(store.settings.first().previewSound)

        store.setPreviewSound(false)
        assertFalse(store.settings.first().previewSound)
    }

    @Test
    fun `a file that is not a preferences store reads as defaults and is replaced`() = runTest {
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(file).buffered().use { it.write(byteArrayOf(0x7f, 0x00, 0x7f, 0x7f, 0x7f)) }

        assertEquals(AppSettings(), newStore().settings.first())
        val store = newStore()
        store.setLastChannel("3")
        assertEquals("3", store.settings.first().lastChannel)
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
