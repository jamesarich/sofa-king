package dev.jamesrich.sofaking.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserStorageTest {
    // Node has no window; the stub makes `window.localStorage` throw as a browser does when it blocks storage.
    private fun denyStorage() {
        js(
            """
            globalThis.window = globalThis;
            Object.defineProperty(globalThis, 'localStorage', {
                configurable: true,
                get: function () { throw new DOMException('Access is denied for this document.', 'SecurityError'); }
            });
            """,
        )
    }

    private fun restoreStorage() {
        js("delete globalThis.localStorage; delete globalThis.window;")
    }

    @Test
    fun `settings and the lineup cache stay in memory when the browser denies storage`() = runTest {
        denyStorage()
        try {
            val storage = AppStorage.inBrowser("denied")
            val key = stringPreferencesKey("backend_url")

            assertNull(storage.settings.data.first()[key])
            storage.settings.edit { it[key] = "http://sofa.local" }
            assertEquals("http://sofa.local", storage.settings.data.first()[key])

            assertNull(storage.lineupCache.data.first())
        } finally {
            restoreStorage()
        }
    }
}
