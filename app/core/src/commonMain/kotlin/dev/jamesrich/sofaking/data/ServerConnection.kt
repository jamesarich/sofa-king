package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A server address and token the API can be built from, and the one API client built from
 * them, which the connection closes.
 */
internal data class Server(val url: String, val token: String, val api: SofaKingApi)

/**
 * The server Settings names, for the whole app: the one API client for it, what it offers, the one playback session
 * on it and the guide's recording marks. It lives as long as the process, in [scope], and every ViewModel reads the
 * server through it, so none reaches into another. A failure nothing in its work handles, such as a settings write
 * the store refused during the sync, is logged rather than crashing the process. [newApi] builds the API client for
 * an address and token; tests aim it at a fake server.
 */
class ServerConnection(
    settings: Flow<AppSettings?>,
    scope: CoroutineScope,
    /** The one playback session the app holds; screens tune through it and the host releases it when stopped. */
    val sessions: PlaybackSessions = PlaybackSessions(scope + Dispatchers.Default + logFailures),
    internal val newApi: (url: String, token: String) -> SofaKingApi = ::SofaKingApi,
    /** Keeps the synced preferences in step with the server; null leaves them on this device only. */
    viewerSync: ViewerSync? = null,
) {
    private val work = scope + logFailures

    /**
     * The server as Settings has it, with one API client for the sessions and the repositories, or null while
     * the address is not a usable URL.
     */
    internal val server: SharedFlow<Server?> = settings.filterNotNull()
        .map { s -> ServerAddress.normalizeUrl(s.serverUrl)?.let { it to s.apiToken } }
        .distinctUntilChanged()
        .map { found -> found?.let { (url, token) -> Server(url, token, newApi(url, token)) } }
        .shareIn(work, SharingStarted.Eagerly, replay = 1)

    /** The API client for the server Settings names, or null while there is none. */
    val api: Flow<SofaKingApi?> = server.map { it?.api }

    init {
        // A new address or token releases the session held on the old server before anything tunes on the new one,
        // then closes the old server's client; the last one closes when the connection's scope ends.
        work.launch {
            var open: SofaKingApi? = null
            try {
                server.collect {
                    sessions.configure(it?.api)
                    open?.close()
                    open = it?.api
                }
            } finally {
                open?.close()
            }
        }
        if (viewerSync != null) work.launch { viewerSync.run(api) }
    }

    /**
     * What the server offers beyond live TV, from its bootstrap: nothing until it answers,
     * or on a server too old to say.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val capabilities: StateFlow<ServerCapabilities> = server
        .transformLatest { found ->
            emit(ServerCapabilities())
            if (found != null) emit(readCapabilities(found.api))
        }
        .stateIn(work, SharingStarted.Eagerly, ServerCapabilities())

    /** Recording from the guide, on a server that records. */
    val recordings = RecordingSchedule(work, api, capabilities.map { it.recordings })

    /** The household's watchlist, on a server that keeps one. */
    val watchlist = WatchlistBook(work, api, capabilities.map { it.watchlist })

    /** What TMDB says about live programs, for the break screen. */
    val programFacts = ProgramFactsBook(api)

    /** Asks until the server answers, backing off to every five minutes; a server without the route offers nothing. */
    private suspend fun readCapabilities(api: SofaKingApi): ServerCapabilities {
        var wait = 5.seconds
        while (true) {
            runCatchingCancellable {
                return api.bootstrap().capabilities.let {
                    ServerCapabilities(recordings = it.recordings, library = it.library, watchlist = it.watchlist)
                }
            }.onFailure { e ->
                if (e is SofaKingException) {
                    if (e.statusCode == HttpStatusCode.NotFound) return ServerCapabilities()
                    log.w { "bootstrap refused: ${e.code}" }
                } else {
                    log.w { "bootstrap failed: ${e.message}" }
                }
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(5.minutes)
        }
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
    }
}
