package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.io.files.Path

/** Where the app keeps its settings and its last good lineup; each platform's entry point supplies one. */
class AppStorage(
    val settings: DataStore<Preferences>,
    val lineupCache: ByteStore,
) {
    companion object {
        /**
         * Both stores under [directory], at the paths the Android app has always used (`datastore/settings.preferences_pb`
         * and `lineup-cache.bin` under its files directory), so an upgrade keeps them.
         */
        fun inDirectory(directory: String): AppStorage = AppStorage(
            settings = preferencesDataStore(Path(directory, "datastore", "settings.preferences_pb").toString()),
            lineupCache = FileByteStore(Path(directory, "lineup-cache.bin")),
        )
    }
}
