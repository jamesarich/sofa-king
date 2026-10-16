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
    /** How the device introduces itself to the server; the entry point knows the platform. */
    val client: ClientInfo = ClientInfo(),
) {
    companion object
}
