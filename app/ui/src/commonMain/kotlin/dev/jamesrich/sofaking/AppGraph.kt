package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.SettingsStore

/** The app's objects over a platform's [storage]: one settings store per file, shared by every lineup ViewModel. */
internal class AppGraph(private val storage: AppStorage) {
    private val settings: SettingsStore = DataStoreSettingsStore(storage.settings)

    fun lineupViewModel(): LineupViewModel = LineupViewModel(store = settings, lineupCache = storage.lineupCache, client = storage.client)
}

private var graph: AppGraph? = null

/** The graph [initSofaKing] started. */
internal val appGraph: AppGraph
    get() = checkNotNull(graph) { "initSofaKing has not run" }

/**
 * Starts the app's object graph over [storage]; each platform's entry point calls it once per process, before
 * the first [App], because DataStore allows one instance per file and composition can be recreated.
 */
fun initSofaKing(storage: AppStorage) {
    check(graph == null) { "initSofaKing runs once per process" }
    graph = AppGraph(storage)
}
