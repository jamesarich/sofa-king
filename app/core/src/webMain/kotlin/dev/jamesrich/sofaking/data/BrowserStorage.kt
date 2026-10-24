package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.WebLocalStorage
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Settings and the lineup cache in DataStore's own localStorage stores, which outlive the tab and coordinate writes
 * across tabs. Settings an older build kept under `<prefix>.settings` move over once on the first read; a lineup cache
 * an older build wrote by hand under `<prefix>.lineupCache` does not decode and is replaced.
 */
fun AppStorage.Companion.inBrowser(prefix: String = "sofaking"): AppStorage = AppStorage(
    settings = PreferenceDataStoreFactory.create(
        storage = WebLocalStorage(PreferencesSerializer, "$prefix.preferences"),
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        migrations = listOf(LegacyLocalStorageMigration("$prefix.settings")),
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
    ),
    lineupCache = DataStoreFactory.create(
        storage = WebLocalStorage(CachedLineupSerializer, "$prefix.lineupCache"),
        corruptionHandler = ReplaceFileCorruptionHandler { null },
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
    ),
    client = ClientInfo("web", "Browser"),
)

/**
 * Moves the hand-written store's one JSON object, each value tagged with its type, into DataStore, then removes it.
 * An entry that cannot be read is dropped rather than failing every read of the settings.
 */
internal class LegacyLocalStorageMigration(private val key: String) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = localStorage.getItem(key) != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val text = localStorage.getItem(key) ?: return currentData
        val prefs = currentData.toMutablePreferences()
        try {
            decodeInto(prefs, text)
        } catch (_: Exception) {
            return currentData
        }
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {
        localStorage.removeItem(key)
    }

    private fun decodeInto(prefs: MutablePreferences, text: String) {
        for ((name, tagged) in Json.parseToJsonElement(text).jsonObject) {
            val entry = tagged.jsonObject
            val value = entry.getValue("v")
            when (entry.getValue("t").jsonPrimitive.content) {
                "b" -> prefs[booleanPreferencesKey(name)] = value.jsonPrimitive.boolean

                "i" -> prefs[intPreferencesKey(name)] = value.jsonPrimitive.int

                "l" -> prefs[longPreferencesKey(name)] = value.jsonPrimitive.long

                "f" -> prefs[floatPreferencesKey(name)] = value.jsonPrimitive.float

                "d" -> prefs[doublePreferencesKey(name)] = value.jsonPrimitive.double

                "s" -> prefs[stringPreferencesKey(name)] = value.jsonPrimitive.content

                "ss" -> prefs[stringSetPreferencesKey(name)] =
                    value.jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content }
            }
        }
    }
}
