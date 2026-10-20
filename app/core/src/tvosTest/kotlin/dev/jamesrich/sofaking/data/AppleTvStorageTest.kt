package dev.jamesrich.sofaking.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import okio.Buffer
import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleTvStorageTest {
    private val server = stringPreferencesKey("serverUrl")
    private val token = stringPreferencesKey("apiToken")

    private val suite = "sofaking-test-${Random.nextLong().toULong()}"
    private val defaults = NSUserDefaults(suiteName = suite)
    private val caches = testDirectory()

    /** Where an older build kept the settings, under Caches. */
    private val oldFile = Path(caches, "datastore", "settings.preferences_pb")

    @AfterTest
    fun tearDown() {
        defaults.removePersistentDomainForName(suite)
        SystemFileSystem.delete(oldFile, mustExist = false)
        SystemFileSystem.delete(Path(caches, "datastore"), mustExist = false)
        SystemFileSystem.delete(caches, mustExist = false)
    }

    @Test
    fun `settings are kept in UserDefaults and a new store reads them back`() = runTest {
        val storage = AppStorage.onAppleTv(caches.toString(), defaults)
        storage.settings.edit { it[server] = "http://tv.test" }

        assertNotNull(defaults.dataForKey("sofaking.preferences"))
        assertFalse(SystemFileSystem.exists(oldFile), "nothing is written under Caches")
        val reopened = PreferenceDataStoreFactory.create(storage = UserDefaultsStorage(defaults, "sofaking.preferences"))
        assertEquals("http://tv.test", reopened.data.first()[server])
    }

    @Test
    fun `settings cleared to nothing read back empty`() = runTest {
        val storage = AppStorage.onAppleTv(caches.toString(), defaults)
        storage.settings.edit { it[server] = "http://tv.test" }
        storage.settings.edit { it.clear() }

        val reopened = PreferenceDataStoreFactory.create(storage = UserDefaultsStorage(defaults, "sofaking.preferences"))
        assertTrue(reopened.data.first().asMap().isEmpty())
    }

    @Test
    fun `settings an older build kept under Caches move over once and the file goes`() = runTest {
        writeOldFile(bytesOf(preferencesOf(server to "http://old.test", token to "secret")))

        val storage = AppStorage.onAppleTv(caches.toString(), defaults)
        val moved = storage.settings.data.first()

        assertEquals("http://old.test", moved[server])
        assertEquals("secret", moved[token])
        assertFalse(SystemFileSystem.exists(oldFile))
        val reopened = PreferenceDataStoreFactory.create(storage = UserDefaultsStorage(defaults, "sofaking.preferences"))
        assertEquals("http://old.test", reopened.data.first()[server], "the moved settings are in UserDefaults")
    }

    @Test
    fun `an unreadable old file is dropped and the settings stay`() = runTest {
        val storage = AppStorage.onAppleTv(caches.toString(), defaults)
        storage.settings.edit { it[server] = "http://tv.test" }
        writeOldFile(byteArrayOf(0x7f, 0x00, 0x13))

        val again = AppStorage.onAppleTv(caches.toString(), defaults)

        assertEquals("http://tv.test", again.settings.data.first()[server])
        assertNull(again.settings.data.first()[token])
        assertFalse(SystemFileSystem.exists(oldFile))
    }

    private suspend fun bytesOf(preferences: Preferences): ByteArray {
        val buffer = Buffer()
        PreferencesSerializer.writeTo(preferences, buffer)
        return buffer.readByteArray()
    }

    private fun writeOldFile(bytes: ByteArray) {
        SystemFileSystem.createDirectories(Path(caches, "datastore"))
        SystemFileSystem.sink(oldFile).buffered().use { it.write(bytes) }
    }
}
