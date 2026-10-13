package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64

/** Settings and the lineup cache in the browser's localStorage, which outlives the tab (DataStore's own web store does not). */
fun AppStorage.Companion.inBrowser(prefix: String = "sofaking"): AppStorage = AppStorage(
    settings = LocalStorageDataStore("$prefix.settings"),
    lineupCache = LocalStorageByteStore("$prefix.lineupCache"),
)

/** A [ByteStore] in one localStorage entry, base64-encoded; a write over the quota throws and keeps the previous entry. */
class LocalStorageByteStore(private val key: String) : ByteStore {
    override fun read(): ByteArray? = localStorage.getItem(key)?.let { Base64.decode(it) }

    override fun write(bytes: ByteArray) {
        localStorage.setItem(key, Base64.encode(bytes))
    }

    override fun delete() {
        localStorage.removeItem(key)
    }
}

/**
 * Preferences as one JSON object in localStorage, each value tagged with its type so its key can be rebuilt.
 * Single-tab: another tab's writes are seen on the next load, not live.
 */
internal class LocalStorageDataStore(private val key: String) : DataStore<Preferences> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        val updated = transform(state.value).toPreferences()
        localStorage.setItem(key, encode(updated))
        state.value = updated
        updated
    }

    private fun load(): Preferences = try {
        localStorage.getItem(key)?.let(::decode) ?: emptyPreferences()
    } catch (_: Exception) {
        emptyPreferences()
    }

    private fun encode(prefs: Preferences): String = JsonObject(
        prefs.asMap().entries.associate { (key, value) ->
            val tagged = when (value) {
                is Boolean -> tag("b", JsonPrimitive(value))
                is Int -> tag("i", JsonPrimitive(value))
                is Long -> tag("l", JsonPrimitive(value))
                is Float -> tag("f", JsonPrimitive(value))
                is Double -> tag("d", JsonPrimitive(value))
                is String -> tag("s", JsonPrimitive(value))
                is Set<*> -> tag("ss", JsonArray(value.map { JsonPrimitive(it as String) }))
                else -> error("unsupported preference type for ${key.name}")
            }
            key.name to tagged
        },
    ).toString()

    private fun decode(text: String): Preferences {
        val prefs = emptyPreferences().toMutablePreferences()
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
                "ss" -> prefs[stringSetPreferencesKey(name)] = value.jsonArray.mapTo(HashSet()) { it.jsonPrimitive.content }
            }
        }
        return prefs.toPreferences()
    }

    private fun tag(type: String, value: kotlinx.serialization.json.JsonElement) =
        JsonObject(mapOf("t" to JsonPrimitive(type), "v" to value))
}
