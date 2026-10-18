package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.BufferPreference
import dev.jamesrich.sofaking.data.ClockFormat
import dev.jamesrich.sofaking.data.DecoderPreference
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.StartupTarget
import androidx.datastore.core.DataStore
import dev.jamesrich.sofaking.data.CachedLineup
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.PairingClaimDto
import dev.jamesrich.sofaking.data.ViewerSync
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LineupCache
import dev.jamesrich.sofaking.data.GuideWindow
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.RecordingSchedule
import dev.jamesrich.sofaking.data.ServerAddress
import dev.jamesrich.sofaking.data.ServerCapabilities
import dev.jamesrich.sofaking.data.SettingsPin
import dev.jamesrich.sofaking.data.SettingsStore
import dev.jamesrich.sofaking.data.UpdateChecker
import dev.jamesrich.sofaking.data.UpdateState
import dev.jamesrich.sofaking.data.SofaKingApi
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.SofaKingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

sealed interface LineupState {
    data object Loading : LineupState

    /** No usable server address yet; Settings is where it goes. */
    data object Unconfigured : LineupState
    data class Ready(val lineup: Lineup) : LineupState
    data class Failed(val message: String) : LineupState
}

/** Why a pairing code was not accepted: the code itself, a server too old to pair, or no server at that address. */
enum class PairingFailure { INVALID_CODE, OLDER_SERVER, UNREACHABLE }

/** A server address and token the API can be built from, and the one API client built from them. */
private data class Server(val url: String, val token: String, val api: SofaKingApi)

/**
 * The lineup, the settings and the one playback session, for every screen. [newApi] builds the API client for a
 * server address and token; tests swap it for one aimed at a fake server.
 */
class LineupViewModel(
    private val store: SettingsStore,
    private val lineupCache: DataStore<CachedLineup?>,
    /** The one playback session the app holds; screens tune through it and the host releases it when stopped. */
    val sessions: PlaybackSessions = PlaybackSessions(),
    private val newApi: (url: String, token: String) -> SofaKingApi = ::SofaKingApi,
    /** How this device names itself when it pairs and when it saves its preferences on the server. */
    private val client: ClientInfo = ClientInfo(),
    /** Keeps the synced preferences in step with the server; null leaves them on this device only. */
    private val viewerSync: ViewerSync? = ViewerSync(store, client),
    /** Reads the published snapshot for the update check in Settings. */
    private val updateChecker: UpdateChecker = UpdateChecker(),
) : ViewModel() {
    private val _update = MutableStateFlow<UpdateState?>(null)

    /** Where the running build stands against the published snapshot; null until [checkForUpdate] has run. */
    val update: StateFlow<UpdateState?> = _update.asStateFlow()

    val settings: StateFlow<AppSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Each reload request; one from [reloadLineup] carries the deferred its caller waits on. */
    private val reloads = MutableSharedFlow<CompletableDeferred<Lineup?>?>(extraBufferCapacity = 1)

    private val _loading = MutableStateFlow(false)

    /** True while the catalog and guide are being fetched, including a background or manual refresh. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** The last lineup loaded, with the server key it came from; read and written only inside [state]'s transform. */
    private var lastGood: Pair<String, Lineup>? = null

    /**
     * Guide windows beyond the live one that the guide jumped to, by their start: loaded once each and merged
     * into the lineup until the live window passes them. Never cached; a cold start shows today's guide.
     */
    private val extraDays = MutableStateFlow<Map<Instant, Map<String, List<Programme>>>>(emptyMap())

    /** The windows being fetched, so a repeated jump does not query twice; read and written on the main thread. */
    private val daysLoading = HashSet<Instant>()

    /** The repository of the lineup on screen, for an extra day's query; set by [load]. */
    private var liveRepository: SofaKingRepository? = null

    /** The server as Settings has it, with one API client for the sessions and the repository, or null while the address is not a usable URL. */
    private val server = settings.filterNotNull()
        .map { s -> ServerAddress.normalizeUrl(s.serverUrl)?.let { it to s.apiToken } }
        .distinctUntilChanged()
        .map { found -> found?.let { (url, token) -> Server(url, token, newApi(url, token)) } }
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** The API client for the server Settings names, or null while there is none; the library screens read through it. */
    val api: Flow<SofaKingApi?> = server.map { it?.api }

    init {
        // A new address or token releases the session held on the old server before anything tunes on the new one.
        viewModelScope.launch {
            server.collect { sessions.configure(it?.api) }
        }
        if (viewerSync != null) viewModelScope.launch { viewerSync.run(server.map { it?.api }) }
    }

    /** What the server offers beyond live TV, from its bootstrap: nothing until it answers, or on a server too old to say. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val capabilities: StateFlow<ServerCapabilities> = server
        .transformLatest { found ->
            emit(ServerCapabilities())
            if (found != null) emit(readCapabilities(found.api))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ServerCapabilities())

    /** Recording from the guide, on a server that records. */
    val recordings = RecordingSchedule(viewModelScope, server.map { it?.api }, capabilities.map { it.recordings })

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

    private val _pairing = MutableStateFlow(false)

    /** True while a pairing code is being claimed. */
    val pairing: StateFlow<Boolean> = _pairing.asStateFlow()

    /**
     * Claims the pairing [code] at [url] and, when the server accepts it, saves the address and the token it hands
     * out. Returns null on success, else why it failed; nothing is saved then.
     */
    suspend fun pair(url: String, code: String): PairingFailure? {
        val server = ServerAddress.normalizeUrl(url) ?: return PairingFailure.INVALID_CODE
        val digits = ServerAddress.normalizeCode(code) ?: return PairingFailure.INVALID_CODE
        _pairing.value = true
        try {
            val clientId = store.ensureClientId()
            val result = newApi(server, "").pair(PairingClaimDto(digits, clientId, client.deviceName, client.platform))
            store.setServer(server, result.apiToken)
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: SofaKingException) {
            log.w { "pairing refused: ${e.code}" }
            return when {
                e.code == Wire.ErrorCode.PAIRING_INVALID -> PairingFailure.INVALID_CODE
                e.status == 404 || e.status == 401 -> PairingFailure.OLDER_SERVER
                else -> PairingFailure.UNREACHABLE
            }
        } catch (e: Exception) {
            log.w { "pairing failed: ${e.message}" }
            return PairingFailure.UNREACHABLE
        } finally {
            _pairing.value = false
        }
    }

    /** One repository per server, so its catalog ETag survives every refresh; rebuilt only when the server changes. */
    private val serverRepository = server.map { s -> s?.let { it to SofaKingRepository(it.api) } }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveState: StateFlow<LineupState> = combine(serverRepository, reloads.onStart { emit(null) }) { found, request -> found to request }
        .transformLatest { (found, request) ->
            // The request, if any, is answered by the first load below; a server change or another reload that
            // cancels this transform answers it with nothing.
            var pending = request
            // A new server starts without the old one's extra days.
            extraDays.value = emptyMap()
            try {
                load(found, onCatalog = { lineup -> pending?.complete(lineup); pending = null })
            } finally {
                pending?.complete(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LineupState.Loading)

    val state: StateFlow<LineupState> = combine(liveState, extraDays) { live, extra ->
        if (live !is LineupState.Ready || extra.isEmpty()) live else LineupState.Ready(live.lineup.copy(guide = mergeGuides(live.lineup.guide, extra.values)))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LineupState.Loading)

    /**
     * Loads the programmes of the guide window starting at [windowStart] for the channels on screen, when it lies
     * beyond the live window and is not loaded yet. The lineup then carries both days; a failure leaves the window
     * empty, and the next jump to it asks again.
     */
    fun loadGuideWindow(windowStart: Instant) {
        val live = GuideWindow.start(Clock.System.now())
        if (windowStart <= live) return
        val lineup = (liveState.value as? LineupState.Ready)?.lineup ?: return
        val repository = liveRepository ?: return
        if (windowStart in extraDays.value || !daysLoading.add(windowStart)) return
        viewModelScope.launch {
            try {
                val started = TimeSource.Monotonic.markNow()
                val guide = repository.loadGuide(lineup.channels, windowStart)
                log.i { "guide: day ${windowStart} ${guide.programmes.values.sumOf { it.size }} programmes in ${started.elapsedNow()}" }
                extraDays.update { it + (windowStart to guide.programmes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "guide: day ${windowStart} failed" }
            } finally {
                daysLoading.remove(windowStart)
            }
        }
    }

    /** Loads [found]'s lineup, cache first, then from the network every [REFRESH_INTERVAL]; [onCatalog] gets the first load's catalog, or null when it fails. */
    private suspend fun FlowCollector<LineupState>.load(found: Pair<Server, SofaKingRepository>?, onCatalog: (Lineup?) -> Unit) {
        if (found == null) {
            emit(LineupState.Unconfigured)
            return
        }
        val (server, repository) = found
        val key = server.url
        liveRepository = repository
        val cache = LineupCache(lineupCache, key)
        // A refresh keeps the lineup already on screen; a cold start or a server change reads the cache,
        // so the last good lineup shows at once and the network load replaces it below.
        var last: Lineup? = lastGood?.takeIf { it.first == key }?.second
        if (last == null) {
            val cacheStarted = TimeSource.Monotonic.markNow()
            cache.read()?.let { cached ->
                log.i { "cache: ${cached.guide.values.sumOf { it.size }} programmes in ${cacheStarted.elapsedNow()}" }
                last = cached
                lastGood = key to cached
                emit(LineupState.Ready(cached))
            }
        }
        while (true) {
            if (last == null) emit(LineupState.Loading)
            // Cancellation (a server change or refresh while loading) must propagate, not read as a failure.
            _loading.value = true
            var lineupMoved = false
            var interim: Lineup? = null
            val result = try {
                val started = TimeSource.Monotonic.markNow()
                val channels = repository.loadChannels()
                log.i { "lineup: ${channels.size} channels in ${started.elapsedNow()}" }
                // Channels first so the guide is usable while the programmes arrive. A cached guide stays in
                // place for channels it still covers; it is replaced once the new guide is in.
                if (last?.channels != channels) {
                    interim = Lineup(channels, last?.guide.orEmpty(), guideLoading = true)
                    emit(LineupState.Ready(interim))
                }
                onCatalog(interim ?: last)
                val guide = repository.loadGuide(channels)
                log.i { "guide: ${guide.programmes.values.sumOf { it.size }} programmes for ${guide.programmes.size} channels in ${started.elapsedNow()}" }
                // The lineup changed between the catalog and the guide: drop what left and read the catalog again soon.
                lineupMoved = guide.removed.isNotEmpty()
                if (lineupMoved) log.i { "guide: ${guide.removed.size} channels left the lineup" }
                Result.success(Lineup(channels.filterNot { it.guideId in guide.removed }, guide.programmes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                // Also on cancellation, or a refresh or server change mid-load leaves the loader spinning.
                _loading.value = false
            }
            result.onSuccess {
                last = it
                lastGood = key to it
                emit(LineupState.Ready(it))
                val live = GuideWindow.start(Clock.System.now())
                extraDays.update { days -> days.filterKeys { start -> start > live } }
                // A cache write failure must not fail the load or delay the next refresh.
                try {
                    cache.write(it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "lineup cache write failed" }
                }
            }
            // A failed background refresh keeps showing the lineup we already have.
            result.onFailure {
                onCatalog(null)
                val shown = interim
                when {
                    // The new channels stay on screen without programmes until a retry brings the guide; they are
                    // not cached, so a cold start still shows the last complete lineup.
                    shown != null -> {
                        val kept = shown.copy(guideLoading = false)
                        last = kept
                        emit(LineupState.Ready(kept))
                    }
                    last == null -> emit(LineupState.Failed(describe(it)))
                }
            }
            delay(if (result.isSuccess && !lineupMoved) REFRESH_INTERVAL else RETRY_INTERVAL)
        }
    }

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

    fun saveServer(url: String, apiToken: String) = viewModelScope.launch { store.setServer(url, apiToken) }

    fun toggleFavorite(guideId: String) = viewModelScope.launch { store.toggleFavorite(guideId) }

    fun setLastChannel(guideId: String) = viewModelScope.launch { store.setLastChannel(guideId) }

    fun setGuideFilter(key: String?) = viewModelScope.launch { store.setGuideFilter(key) }

    fun setCaptionsEnabled(enabled: Boolean) = viewModelScope.launch { store.setCaptionsEnabled(enabled) }

    fun setCaptionAdvanceMs(ms: Int) = viewModelScope.launch { store.setCaptionAdvanceMs(ms) }

    fun setChannelCaptionAdvance(guideId: String, ms: Int) =
        viewModelScope.launch { store.setChannelCaptionAdvance(guideId, ms) }

    fun setSmoothCaptions(enabled: Boolean) = viewModelScope.launch { store.setSmoothCaptions(enabled) }

    fun setCaptionStyle(style: CaptionStyle) = viewModelScope.launch { store.setCaptionStyle(style) }

    fun setKeyLegendShown(count: Int) = viewModelScope.launch { store.setKeyLegendShown(count) }

    fun setAutoOpenDelay(delay: AutoOpenDelay) = viewModelScope.launch { store.setAutoOpenDelay(delay) }

    fun setPreviewSound(enabled: Boolean) = viewModelScope.launch { store.setPreviewSound(enabled) }

    fun setStartup(target: StartupTarget, guideId: String? = null) = viewModelScope.launch { store.setStartup(target, guideId) }

    fun setClockFormat(format: ClockFormat) = viewModelScope.launch { store.setClockFormat(format) }

    fun setGuideDensity(density: GuideDensity) = viewModelScope.launch { store.setGuideDensity(density) }

    fun setChannelHidden(guideId: String, hidden: Boolean) = viewModelScope.launch { store.setChannelHidden(guideId, hidden) }

    fun setChannelsHidden(guideIds: Collection<String>, hidden: Boolean) = viewModelScope.launch { store.setChannelsHidden(guideIds, hidden) }

    fun setZapSkipsHidden(enabled: Boolean) = viewModelScope.launch { store.setZapSkipsHidden(enabled) }

    fun setFavoritesOrder(order: List<String>) = viewModelScope.launch { store.setFavoritesOrder(order) }

    fun setMatchFrameRate(enabled: Boolean) = viewModelScope.launch { store.setMatchFrameRate(enabled) }

    fun setDecoder(decoder: DecoderPreference) = viewModelScope.launch { store.setDecoder(decoder) }

    fun setChannelDecoder(guideId: String, decoder: DecoderPreference?) = viewModelScope.launch { store.setChannelDecoder(guideId, decoder) }

    fun setBuffer(buffer: BufferPreference) = viewModelScope.launch { store.setBuffer(buffer) }

    /**
     * Compares [runningVersion] with the published snapshot, once per process unless [again] is set, since GitHub
     * limits anonymous calls; the answer lands in [update].
     */
    fun checkForUpdate(runningVersion: String, again: Boolean = false) {
        if (!again && _update.value != null) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            val latest = try {
                updateChecker.latest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "update check failed: ${e.message}" }
                null
            }
            _update.value = latest?.let { UpdateChecker.compare(runningVersion, it) } ?: UpdateState.Failed
        }
    }

    /** Locks Settings behind [pin], stored as its hash, or removes the lock for null. */
    fun setSettingsPin(pin: String?) = viewModelScope.launch { store.setSettingsPin(pin?.let(SettingsPin::hash)) }

    override fun onCleared() {
        sessions.close()
    }

    /** [live] plus every day in [extra], per channel in start order without repeats, so a day loaded twice reads once. */
    private fun mergeGuides(live: Map<String, List<Programme>>, extra: Collection<Map<String, List<Programme>>>): Map<String, List<Programme>> {
        val merged = HashMap<String, List<Programme>>(live)
        for (day in extra) {
            for ((channel, programmes) in day) {
                val current = merged[channel]
                merged[channel] = if (current == null) programmes else (current + programmes).distinctBy { it.start }.sortedBy { it.start }
            }
        }
        return merged
    }

    /** The server's own error code when it answered, else the transport's message; never a URL or token. */
    private fun describe(failure: Throwable): String = when (failure) {
        is SofaKingException -> "${failure.code}: ${failure.message}"
        else -> failure.message ?: failure.toString()
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
        val REFRESH_INTERVAL = 3.hours
        val RETRY_INTERVAL = 1.minutes
        val RELOAD_WAIT = 30.seconds
    }
}
