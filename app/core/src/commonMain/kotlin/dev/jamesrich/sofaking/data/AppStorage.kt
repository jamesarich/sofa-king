package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * Where the app keeps its settings and its last good lineup; each platform's entry point supplies one, from files
 * ([inDirectory]) or the browser's storage ([inBrowser]).
 */
class AppStorage(
    val settings: DataStore<Preferences>,
    val lineupCache: DataStore<CachedLineup?>,
) {
    companion object
}
