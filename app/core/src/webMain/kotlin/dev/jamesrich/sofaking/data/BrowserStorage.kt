package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.InterProcessCoordinator
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
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
import co.touchlab.kermit.Logger
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.w3c.dom.Storage as DomStorage

/**
 * Settings and the lineup cache in DataStore's own localStorage stores, which outlive the tab and coordinate writes
 * across tabs. Settings an older build kept under `<prefix>.settings` move over once on the first read; a lineup cache
 * an older build wrote by hand under `<prefix>.lineupCache` does not decode and is replaced. A page whose storage the
 * browser denies keeps both in memory for that page load.
 */
fun AppStorage.Companion.inBrowser(prefix: String = "sofaking"): AppStorage {
    val guard = LocalStorageGuard(prefix)
    return AppStorage(
        settings = PreferenceDataStoreFactory.create(
            storage = guard.storage(PreferencesSerializer, "$prefix.preferences"),
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            migrations = listOf(LegacyLocalStorageMigration("$prefix.settings", guard)),
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
        ),
        lineupCache = DataStoreFactory.create(
            storage = guard.storage(CachedLineupSerializer, "$prefix.lineupCache"),
            corruptionHandler = ReplaceFileCorruptionHandler { null },
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
        ),
        client = ClientInfo("web", "Browser"),
    )
}

/**
 * Reading `window.localStorage` throws a `SecurityError` when the browser blocks the document's storage. The first
 * probe decides for the page load: the stores then use localStorage, or stay in memory with one warning.
 */
internal class LocalStorageGuard(private val prefix: String) {
    /** localStorage, or null when the browser denies it. */
    @Suppress("TooGenericExceptionCaught") // The browser's SecurityError is a foreign JS error, not an Exception.
    val local: DomStorage? by lazy {
        try {
            localStorage.also {
                it.setItem("$prefix.probe", "1")
                it.removeItem("$prefix.probe")
            }
        } catch (error: Throwable) {
            Logger.withTag("SofaKing").w { "Browser storage is denied, so settings last only for this page: $error" }
            null
        }
    }

    fun <T> storage(serializer: OkioSerializer<T>, name: String): Storage<T> =
        if (local == null) MemoryStorage(serializer.defaultValue) else WebLocalStorage(serializer, name)
}

/** One value that lives as long as the page; every connection to it sees the same one. */
private class MemoryStorage<T>(private var value: T) : Storage<T> {
    private val coordinator = MemoryCoordinator()

    override fun createConnection(): StorageConnection<T> = object : StorageConnection<T> {
        override val coordinator: InterProcessCoordinator = this@MemoryStorage.coordinator

        // DataStore already holds the coordinator's lock around every read and write, so the scopes take none.
        override suspend fun <R> readScope(block: suspend ReadScope<T>.(locked: Boolean) -> R): R =
            MemoryScope().block(true)

        override suspend fun writeScope(block: suspend WriteScope<T>.() -> Unit) {
            MemoryScope().block()
        }

        override fun close() = Unit
    }

    private inner class MemoryScope : WriteScope<T> {
        override suspend fun readData(): T = value

        override suspend fun writeData(value: T) {
            this@MemoryStorage.value = value
        }

        override fun close() = Unit
    }
}

private class MemoryCoordinator : InterProcessCoordinator {
    private val mutex = Mutex()
    private var version = 0

    override val updateNotifications: Flow<Unit> = emptyFlow()

    override suspend fun <T> lock(block: suspend () -> T): T = mutex.withLock { block() }

    override suspend fun <T> tryLock(block: suspend (Boolean) -> T): T {
        val locked = mutex.tryLock()
        try {
            return block(locked)
        } finally {
            if (locked) mutex.unlock()
        }
    }

    override suspend fun getVersion(): Int = version

    override suspend fun incrementAndGetVersion(): Int = ++version
}

/**
 * Moves the hand-written store's one JSON object, each value tagged with its type, into DataStore, then removes it.
 * An entry that cannot be read is dropped rather than failing every read of the settings.
 */
internal class LegacyLocalStorageMigration(private val key: String, private val guard: LocalStorageGuard) :
    DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = guard.local?.getItem(key) != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val text = guard.local?.getItem(key) ?: return currentData
        val prefs = currentData.toMutablePreferences()
        try {
            decodeInto(prefs, text)
        } catch (_: Exception) {
            return currentData
        }
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {
        guard.local?.removeItem(key)
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
