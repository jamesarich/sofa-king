package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import okio.Path.Companion.toPath
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [SettingsStore] on a preferences [DataStore]; there must be only one per file, which the DI
 * graph's single instance guarantees.
 */
class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
    /** How long an unreadable store waits before it is read again. */
    private val readRetry: Duration = 5.seconds,
) : SettingsStore,
    ChannelSettings by DataStoreChannelSettings(dataStore),
    CaptionSettings by DataStoreCaptionSettings(dataStore),
    PlayerSettings by DataStorePlayerSettings(dataStore),
    DisplaySettings by DataStoreDisplaySettings(dataStore),
    DeviceSettings by DataStoreDeviceSettings(dataStore) {

    // An unreadable store reads as defaults rather than failing the flow, which would take the whole app down, and is
    // read again after a pause, so the settings return once it reads or a write replaces it.
    override val settings: Flow<AppSettings> = dataStore.data
        .retryWhen { cause, _ ->
            if (cause is CancellationException) return@retryWhen false
            emit(emptyPreferences())
            delay(readRetry)
            true
        }
        .map(LatestRead(::readSettings)::of)
}

/**
 * Reads [read] once per [Preferences] snapshot. DataStore hands every collector the same instance for one state, so
 * the collectors of the settings flow share one read per emission while each keeps its own cold flow, and a collector
 * that starts after a write still gets that write.
 */
internal class LatestRead(private val read: (Preferences) -> AppSettings) {
    private class Read(val from: Preferences, val settings: AppSettings)

    @Volatile private var latest: Read? = null

    fun of(preferences: Preferences): AppSettings {
        latest?.takeIf { it.from === preferences }?.let { return it.settings }
        return read(preferences).also { latest = Read(preferences, it) }
    }
}

/** A preferences store in [file]; a corrupt file is replaced with defaults. Create one per file, as a DI single. */
fun preferencesDataStore(file: String): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = CoroutineScope(ioDispatcher + SupervisorJob()),
) { file.toPath() }
