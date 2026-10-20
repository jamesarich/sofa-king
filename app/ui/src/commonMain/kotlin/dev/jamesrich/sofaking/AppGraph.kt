package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.SettingsStore
import dev.jamesrich.sofaking.data.ViewerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's objects over a platform's [storage]: one settings store per file and one connection to the server it
 * names, shared by every ViewModel. They live as long as the process; the ViewModels, per screen host, read through them.
 */
internal class AppGraph(private val storage: AppStorage) {
    private val settings: SettingsStore = DataStoreSettingsStore(storage.settings)

    /**
     * Started by the first ViewModel rather than with the process, so a process woken for anything else does not call
     * the server. Its flows run on the main thread, as the ViewModels' do.
     */
    private val connection by lazy {
        ServerConnection(
            settings = settings.settings,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            viewerSync = ViewerSync(settings, storage.client),
        )
    }

    fun lineupViewModel(): LineupViewModel = LineupViewModel(store = settings, lineupCache = storage.lineupCache, connection = connection, client = storage.client)

    fun libraryViewModel(): LibraryViewModel = LibraryViewModel(connection.api, connection.sessions)

    /** The Recordings tab reads the same list as the guide's record marks, from the connection. */
    fun recordingsViewModel(): RecordingsViewModel = RecordingsViewModel(connection.recordings, connection.sessions)
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
