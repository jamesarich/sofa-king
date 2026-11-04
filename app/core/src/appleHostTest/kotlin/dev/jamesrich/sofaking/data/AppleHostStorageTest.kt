package dev.jamesrich.sofaking.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleHostStorageTest {
    private val server = stringPreferencesKey("serverUrl")
    private val suite = "sofaking-host-test-${Random.nextLong().toULong()}"
    private val defaults = NSUserDefaults(suiteName = suite)
    private val directory = testDirectory().also { SystemFileSystem.createDirectories(it) }

    @AfterTest
    fun tearDown() {
        defaults.removePersistentDomainForName(suite)
        deleteTree(directory)
    }

    private fun deleteTree(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach(::deleteTree)
        }
        SystemFileSystem.delete(path, mustExist = false)
    }

    @Test
    fun `settings written through onApple read back`() = runTest {
        val settings = AppStorage.onApple(directory.toString(), defaults).settings
        settings.edit { it[server] = "http://apple.test" }

        assertEquals("http://apple.test", settings.data.first()[server])
    }

    @Test
    fun `the client reports the platform it runs on`() {
        val platform = AppStorage.onApple(directory.toString(), defaults).client.platform

        assertTrue(platform == "ios" || platform == "tvos", "platform was $platform")
    }
}
