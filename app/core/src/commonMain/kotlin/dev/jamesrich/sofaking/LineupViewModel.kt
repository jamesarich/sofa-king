package dev.jamesrich.sofaking

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.CachedLineup
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.DiscoveredServer
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.RecordingSchedule
import dev.jamesrich.sofaking.data.ServerCapabilities
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.ServerDiscovery
import dev.jamesrich.sofaking.data.SettingsPin
import dev.jamesrich.sofaking.data.SettingsStore
import dev.jamesrich.sofaking.data.SofaKingRepository
import dev.jamesrich.sofaking.data.UpdateChecker
import dev.jamesrich.sofaking.data.UpdateState
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

sealed interface LineupState {
    data object Loading : LineupState

    /** No usable server address yet; Settings is where it goes. */
    data object Unconfigured : LineupState
    data class Ready(val lineup: Lineup) : LineupState
    data class Failed(val message: String) : LineupState
}

/** Why a pairing code was not accepted: the code itself, a server too old to pair, or no server at that address. */
enum class PairingFailure { INVALID_CODE, OLDER_SERVER, UNREACHABLE }

/** How a pairing attempt ended. */
enum class PairingOutcome { PAIRED, FAILED }

/**
 * Pairing as the screens show it: [busy] while a code is being claimed; [failure], why the last attempt failed, which
 * Settings shows until the next one; and [outcome], the last attempt's end until the screen that asked has acted on it.
 */
data class PairingState(
    val busy: Boolean = false,
    val failure: PairingFailure? = null,
    val outcome: PairingOutcome? = null,
)

/** How long browsing outlives Settings closing its server fields, so a quick reopen keeps what was found. */
private const val DISCOVERY_LINGER_MS = 5_000L

/** The lineup and the settings for every screen, over the app's one [connection] to the server Settings names. */
class LineupViewModel(
    private val store: SettingsStore,
    lineupCache: DataStore<CachedLineup?>,
    private val connection: ServerConnection,
    /** How this device names itself when it pairs. */
    client: ClientInfo = ClientInfo(),
    /** Reads the published snapshot for the update check in Settings. */
    private val updateChecker: UpdateChecker = UpdateChecker(),
    /** Where the guide window and the cache's expiry read the time. */
    clock: Clock = Clock.System,
    /** Where the lineup is decoded, mapped and merged, off the main thread; the state is set on the main thread. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
    /** Finds servers on the network for Settings. */
    discovery: ServerDiscovery = ServerDiscovery.None,
    /** Where this ViewModel's work runs, the main thread's, until it is cleared. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope),
    PairingActions by ServerPairing(store, connection, client, scope),
    ChannelEdits by StoreChannelEdits(store, scope),
    CaptionEdits by StoreCaptionEdits(store, scope),
    PlayerEdits by StorePlayerEdits(store, scope),
    DisplayEdits by StoreDisplayEdits(store, scope) {
    private val _update = MutableStateFlow<UpdateState?>(null)

    /** Where the running build stands against the published snapshot; null until [checkForUpdate] has run. */
    val update: StateFlow<UpdateState?> = _update.asStateFlow()

    val settings: StateFlow<AppSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The servers advertising themselves on the network; it browses only while collected,
     * as Settings' server fields are.
     */
    val discoveredServers: StateFlow<List<DiscoveredServer>> = discovery.servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(DISCOVERY_LINGER_MS), emptyList())

    /** The one playback session the app holds; screens tune through it and the host releases it when stopped. */
    val sessions: PlaybackSessions get() = connection.sessions

    /** What the server offers beyond live TV, from its bootstrap. */
    val capabilities: StateFlow<ServerCapabilities> get() = connection.capabilities

    /** Recording from the guide, on a server that records. */
    val recordings: RecordingSchedule get() = connection.recordings

    /** Each reload request; one from [reloadLineup] carries the deferred its caller waits on. */
    private val reloads = MutableSharedFlow<CompletableDeferred<Lineup?>?>(extraBufferCapacity = 1)

    private val days = GuideDays(clock, viewModelScope)

    private val loader = LineupLoader(lineupCache, clock, background, days)

    /** True while the catalog and guide are being fetched, including a background or manual refresh. */
    val loading: StateFlow<Boolean> get() = loader.loading

    /** One repository per server, so its catalog ETag survives every refresh; rebuilt only when the server changes. */
    private val serverRepository = connection.server.map { s ->
        s?.let {
            it to
                SofaKingRepository(it.api, background, clock::now)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveState: StateFlow<LineupState> = combine(
        serverRepository,
        reloads.onStart {
            emit(null)
        },
    ) { found, request ->
        found to
            request
    }
        .transformLatest { (found, request) ->
            // The request, if any, is answered by the first load below; a server change or another reload that
            // cancels this transform answers it with nothing.
            var pending = request
            days.follow(found?.first?.url)
            try {
                with(loader) {
                    load(found, onCatalog = { lineup ->
                        pending?.complete(lineup)
                        pending = null
                    })
                }
            } finally {
                pending?.complete(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LineupState.Loading)

    val state: StateFlow<LineupState> = combine(liveState, days.loaded) { live, extra ->
        if (live !is LineupState.Ready || extra.isEmpty()) {
            live
        } else {
            LineupState.Ready(
                live.lineup.copy(
                    guide = withContext(background) {
                        mergeGuides(live.lineup.guide, extra.values)
                    },
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LineupState.Loading)

    /**
     * Loads the programs of the guide window starting at [windowStart] for the channels on screen, when it lies
     * beyond the live window and is not loaded yet. The lineup then carries both days; a failure leaves the window
     * empty, and the next jump to it asks again.
     */
    fun loadGuideWindow(windowStart: Instant) =
        days.load(windowStart, (liveState.value as? LineupState.Ready)?.lineup, loader.repository)

    fun refresh() {
        reloads.tryEmit(null)
    }

    /**
     * Asks for a reload and waits for its catalog: the lineup holding the new channels, or null when the load
     * failed or nothing came back in time. The guide may still be loading when it returns.
     */
    suspend fun reloadLineup(): Lineup? {
        val request = CompletableDeferred<Lineup?>()
        if (!reloads.tryEmit(request)) return null
        return withTimeoutOrNull(RELOAD_WAIT) { request.await() }
    }

    fun saveServer(url: String, apiToken: String) {
        pairingDismissed()
        viewModelScope.save { store.setServer(url, apiToken) }
    }

    /**
     * Compares [runningVersion] with the published snapshot, once per process unless [again] is set, since GitHub
     * limits anonymous calls; the answer lands in [update].
     */
    fun checkForUpdate(runningVersion: String, again: Boolean = false) {
        if (!again && _update.value != null) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            val latest = runCatchingCancellable {
                updateChecker.latest()
            }.getOrElse { e ->
                log.w { "update check failed: ${e.message}" }
                null
            }
            _update.value = latest?.let { UpdateChecker.compare(runningVersion, it) } ?: UpdateState.Failed
        }
    }

    /** Locks Settings behind [pin], stored as its hash, or removes the lock for null. */
    fun setSettingsPin(pin: String?) {
        viewModelScope.save { store.setSettingsPin(pin?.let(SettingsPin::hash)) }
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
        val RELOAD_WAIT = 30.seconds
    }
}
