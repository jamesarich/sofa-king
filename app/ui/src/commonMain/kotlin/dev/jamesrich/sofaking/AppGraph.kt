package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.ServerDiscovery
import dev.jamesrich.sofaking.data.SettingsStore
import dev.jamesrich.sofaking.data.ViewerSync
import dev.jamesrich.sofaking.data.WatchNext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

/**
 * The app's objects over a platform's [storage]: one settings store per file and one connection to the server it
 * names, shared by every ViewModel. They live as long as the process; the ViewModels, per
 * screen host, read through them.
 */
internal class AppGraph(
    private val storage: AppStorage,
    private val discovery: ServerDiscovery,
    private val watchNext: WatchNext,
) {
    private val settings: SettingsStore = DataStoreSettingsStore(storage.settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Started by the first ViewModel rather than with the process, so a process woken for anything else does not call
     * the server. Its flows run on the main thread, as the ViewModels' do.
     */
    private val connection by lazy {
        ServerConnection(
            settings = settings.settings,
            scope = scope,
            viewerSync = ViewerSync(settings, storage.client),
        )
    }

    fun lineupViewModel(): LineupViewModel = LineupViewModel(
        store = settings,
        lineupCache = storage.lineupCache,
        connection = connection,
        client = storage.client,
        discovery = discovery,
    )

    fun libraryViewModel(): LibraryViewModel =
        LibraryViewModel(connection.api, connection.sessions, watchNext = watchNext, watchlist = connection.watchlist)

    /** The Recordings tab reads the same list as the guide's record marks, from the connection. */
    fun recordingsViewModel(): RecordingsViewModel = RecordingsViewModel(connection.recordings, connection.sessions)

    /**
     * Saves what a launch passed in before anything reads the settings: a server, as Settings would save it, and the
     * player muted. A debug host's launch arguments use it, for UI tests and checks that cannot type on a simulator.
     */
    suspend fun applyLaunchSettings(serverUrl: String?, apiToken: String?, muted: Boolean) {
        if (serverUrl != null) settings.setServer(serverUrl, apiToken.orEmpty())
        if (muted) settings.setVolume(settings.settings.first().volume, muted = true)
    }

    /** Ends the connection's work; only a test that starts the app again in the same process needs it. */
    fun close() = scope.cancel()
}

private var graph: AppGraph? = null

/** The graph [initSofaKing] started. */
internal val appGraph: AppGraph
    get() = checkNotNull(graph) { "initSofaKing has not run" }

/**
 * Starts the app's object graph over [storage]; each platform's entry point calls it once per process, before
 * the first [App], because DataStore allows one instance per file and composition can be recreated. [discovery]
 * finds servers on the network for Settings, where the platform can browse; [watchNext] is the platform's row of what
 * to watch next outside the app, where it has one.
 */
fun initSofaKing(
    storage: AppStorage,
    discovery: ServerDiscovery = ServerDiscovery.None,
    watchNext: WatchNext = WatchNext.None,
) {
    check(graph == null) { "initSofaKing runs once per process" }
    graph = AppGraph(storage, discovery, watchNext)
}

/** Ends the graph [initSofaKing] started, so a test can start the app again on fresh storage. */
internal fun stopSofaKing() {
    graph?.close()
    graph = null
}
