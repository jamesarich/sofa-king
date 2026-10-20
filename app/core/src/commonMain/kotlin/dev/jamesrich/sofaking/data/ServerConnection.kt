package dev.jamesrich.sofaking.data

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A server address and token the API can be built from, and the one API client built from them. */
internal data class Server(val url: String, val token: String, val api: SofaKingApi)

/**
 * The server Settings names, for the whole app: the one API client for it, what it offers, the one playback session
 * on it and the guide's recording marks. It lives as long as the process, in [scope], and every ViewModel reads the
 * server through it, so none reaches into another. [newApi] builds the API client for an address and token; tests
 * aim it at a fake server.
 */
class ServerConnection(
    settings: Flow<AppSettings?>,
    scope: CoroutineScope,
    /** The one playback session the app holds; screens tune through it and the host releases it when stopped. */
    val sessions: PlaybackSessions = PlaybackSessions(),
    internal val newApi: (url: String, token: String) -> SofaKingApi = ::SofaKingApi,
    /** Keeps the synced preferences in step with the server; null leaves them on this device only. */
    viewerSync: ViewerSync? = null,
) {
    /** The server as Settings has it, with one API client for the sessions and the repositories, or null while the address is not a usable URL. */
    internal val server: SharedFlow<Server?> = settings.filterNotNull()
        .map { s -> ServerAddress.normalizeUrl(s.serverUrl)?.let { it to s.apiToken } }
        .distinctUntilChanged()
        .map { found -> found?.let { (url, token) -> Server(url, token, newApi(url, token)) } }
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** The API client for the server Settings names, or null while there is none. */
    val api: Flow<SofaKingApi?> = server.map { it?.api }

    init {
        // A new address or token releases the session held on the old server before anything tunes on the new one.
        scope.launch {
            server.collect { sessions.configure(it?.api) }
        }
        if (viewerSync != null) scope.launch { viewerSync.run(api) }
    }

    /** What the server offers beyond live TV, from its bootstrap: nothing until it answers, or on a server too old to say. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val capabilities: StateFlow<ServerCapabilities> = server
        .transformLatest { found ->
            emit(ServerCapabilities())
            if (found != null) emit(readCapabilities(found.api))
        }
        .stateIn(scope, SharingStarted.Eagerly, ServerCapabilities())

    /** Recording from the guide, on a server that records. */
    val recordings = RecordingSchedule(scope, api, capabilities.map { it.recordings })

    /** Asks until the server answers, backing off to every five minutes; a server without the route offers nothing. */
    private suspend fun readCapabilities(api: SofaKingApi): ServerCapabilities {
        var wait = 5.seconds
        while (true) {
            try {
                return api.bootstrap().capabilities.let { ServerCapabilities(recordings = it.recordings, library = it.library) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SofaKingException) {
                if (e.status == 404) return ServerCapabilities()
                log.w { "bootstrap refused: ${e.code}" }
            } catch (e: Exception) {
                log.w { "bootstrap failed: ${e.message}" }
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(5.minutes)
        }
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
    }
}
