package dev.jamesrich.sofaking

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.BufferPreference
import dev.jamesrich.sofaking.data.CachedLineup
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.ClockFormat
import dev.jamesrich.sofaking.data.DecoderPreference
import dev.jamesrich.sofaking.data.DiscoveredServer
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.GuideWindow
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LineupCache
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.PairingClaimDto
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.data.RecordingSchedule
import dev.jamesrich.sofaking.data.Server
import dev.jamesrich.sofaking.data.ServerAddress
import dev.jamesrich.sofaking.data.ServerCapabilities
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.ServerDiscovery
import dev.jamesrich.sofaking.data.SettingsPin
import dev.jamesrich.sofaking.data.SettingsStore
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.SofaKingRepository
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.data.TopLevelTab
import dev.jamesrich.sofaking.data.TrackChoice
import dev.jamesrich.sofaking.data.UpdateChecker
import dev.jamesrich.sofaking.data.UpdateState
import dev.jamesrich.sofaking.data.describe
import dev.jamesrich.sofaking.data.logFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
    private val lineupCache: DataStore<CachedLineup?>,
    private val connection: ServerConnection,
    /** How this device names itself when it pairs. */
    private val client: ClientInfo = ClientInfo(),
    /** Reads the published snapshot for the update check in Settings. */
    private val updateChecker: UpdateChecker = UpdateChecker(),
    /** Where the guide window and the cache's expiry read the time. */
    private val clock: Clock = Clock.System,
    /** Where the lineup is decoded, mapped and merged, off the main thread; the state is set on the main thread. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
    /** Finds servers on the network for Settings. */
    discovery: ServerDiscovery = ServerDiscovery.None,
) : ViewModel() {
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

    /** The server [extraDays] were loaded from. */
    private var extraDaysServer: String? = null

    /** The repository of the lineup on screen, for an extra day's query; set by [load]. */
    private var liveRepository: SofaKingRepository? = null

    private val _pairing = MutableStateFlow(PairingState())

    /** Where pairing stands: claiming a code, and how the last attempt went. */
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    /**
     * Claims the pairing [code] at [url] and, when the server accepts it, saves the address and the token it hands
     * out; nothing is saved when it fails. The claim runs in this ViewModel's scope, so leaving the screen or a
     * configuration change does not cut it off between the server's answer and the save, and [pairing] carries the
     * outcome. A second call while one is claimed is ignored.
     */
    fun pair(url: String, code: String) {
        if (_pairing.value.busy) return
        _pairing.value = PairingState(busy = true)
        viewModelScope.launch { claim(url, code) }
    }

    /** [pair] for a host that awaits the answer, as the Apple TV app does: null once paired, else why it failed. */
    suspend fun pairAndWait(url: String, code: String): PairingFailure? =
        viewModelScope.async { claim(url, code) }.await()

    /** The screen that asked has acted on [PairingState.outcome]; the failure stays for Settings to show. */
    fun pairingHandled() {
        _pairing.update { it.copy(outcome = null) }
    }

    /** Settings closed the server block that showed the last failure, or saved another server; it shows no more. */
    fun pairingDismissed() {
        _pairing.update { it.copy(failure = null) }
    }

    private suspend fun claim(url: String, code: String): PairingFailure? {
        _pairing.value = PairingState(busy = true)
        val failure = claimCode(url, code)
        _pairing.value =
            PairingState(
                failure = failure,
                outcome = if (failure == null) {
                    PairingOutcome.PAIRED
                } else {
                    PairingOutcome.FAILED
                },
            )
        return failure
    }

    private suspend fun claimCode(url: String, code: String): PairingFailure? {
        val server = ServerAddress.normalizeUrl(url) ?: return PairingFailure.INVALID_CODE
        val digits = ServerAddress.normalizeCode(code) ?: return PairingFailure.INVALID_CODE
        return try {
            val clientId = store.ensureClientId()
            val result = connection.newApi(server, "").use {
                it.pair(PairingClaimDto(digits, clientId, client.deviceName, client.platform))
            }
            store.setServer(server, result.apiToken)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: SofaKingException) {
            log.w { "pairing refused: ${e.code}" }
            when {
                e.code == Wire.ErrorCode.PAIRING_INVALID -> PairingFailure.INVALID_CODE
                e.status == 404 || e.status == 401 -> PairingFailure.OLDER_SERVER
                else -> PairingFailure.UNREACHABLE
            }
        } catch (e: Exception) {
            log.w { "pairing failed: ${e.message}" }
            PairingFailure.UNREACHABLE
        }
    }

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
            // A new server starts without the old one's extra days; a reload keeps them, since nothing asks again.
            val serverKey = found?.first?.url
            if (serverKey != extraDaysServer) {
                extraDays.value = emptyMap()
                extraDaysServer = serverKey
            }
            try {
                load(found, onCatalog = { lineup ->
                    pending?.complete(lineup)
                    pending = null
                })
            } finally {
                pending?.complete(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LineupState.Loading)

    val state: StateFlow<LineupState> = combine(liveState, extraDays) { live, extra ->
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
     * Loads the programmes of the guide window starting at [windowStart] for the channels on screen, when it lies
     * beyond the live window and is not loaded yet. The lineup then carries both days; a failure leaves the window
     * empty, and the next jump to it asks again.
     */
    fun loadGuideWindow(windowStart: Instant) {
        val live = GuideWindow.start(clock.now())
        if (windowStart <= live) return
        val lineup = (liveState.value as? LineupState.Ready)?.lineup ?: return
        val repository = liveRepository ?: return
        if (windowStart in extraDays.value || !daysLoading.add(windowStart)) return
        val server = extraDaysServer
        viewModelScope.launch {
            try {
                val started = TimeSource.Monotonic.markNow()
                val guide = repository.loadGuide(lineup.channels, windowStart)
                log.i {
                    "guide: day $windowStart ${guide.programmes.values.sumOf { it.size }} programmes in " +
                        "${started.elapsedNow()}"
                }
                // A day that arrives after Settings named another server is the old server's, so it is dropped.
                if (extraDaysServer == server) extraDays.update { it + (windowStart to guide.programmes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "guide: day $windowStart failed" }
            } finally {
                daysLoading.remove(windowStart)
            }
        }
    }

    /**
     * Loads [found]'s lineup, cache first, then from the network every [REFRESH_INTERVAL]; [onCatalog] gets the first
     * load's catalog, or null when it fails.
     */
    private suspend fun FlowCollector<LineupState>.load(
        found: Pair<Server, SofaKingRepository>?,
        onCatalog: (Lineup?) -> Unit,
    ) {
        if (found == null) {
            emit(LineupState.Unconfigured)
            return
        }
        val (server, repository) = found
        val key = server.url
        liveRepository = repository
        val cache = LineupCache(lineupCache, key, background, clock::now)
        // A refresh keeps the lineup already on screen; a cold start or a server change reads the cache,
        // so the last good lineup shows at once and the network load replaces it below. Its channels come first,
        // so a channel or a tab opens while the programmes decode, which takes seconds on a TV.
        var last: Lineup? = lastGood?.takeIf { it.first == key }?.second
        if (last == null) {
            val cacheStarted = TimeSource.Monotonic.markNow()
            cache.open()?.let { cached ->
                log.i { "cache: ${cached.channels.size} channels in ${cacheStarted.elapsedNow()}" }
                emit(LineupState.Ready(Lineup(cached.channels, emptyMap(), guideLoading = true)))
                // A guide that failed to decode, or whose programmes have all ended, is
                // still on its way from the server.
                val guide = cached.guide()
                val full = Lineup(cached.channels, guide.orEmpty(), guideLoading = guide.isNullOrEmpty())
                log.i { "cache: ${full.guide.values.sumOf { it.size }} programmes in ${cacheStarted.elapsedNow()}" }
                last = full
                lastGood = key to full
                emit(LineupState.Ready(full))
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
                log.i {
                    "guide: ${guide.programmes.values.sumOf { it.size }} programmes for ${guide.programmes.size} " +
                        "channels in ${started.elapsedNow()}"
                }
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
                val live = GuideWindow.start(clock.now())
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

                    last == null -> emit(LineupState.Failed(it.describe()))

                    last.guideLoading -> {
                        val kept = last.copy(guideLoading = false)
                        last = kept
                        emit(LineupState.Ready(kept))
                    }
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

    fun saveServer(url: String, apiToken: String) {
        pairingDismissed()
        save { store.setServer(url, apiToken) }
    }

    fun toggleFavorite(guideId: String) {
        save { store.toggleFavorite(guideId) }
    }

    fun setLastChannel(guideId: String) {
        save { store.setLastChannel(guideId) }
    }

    fun setGuideFilter(key: String?) {
        save { store.setGuideFilter(key) }
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        save { store.setCaptionsEnabled(enabled) }
    }

    fun setCaptionAdvanceMs(ms: Int) {
        save { store.setCaptionAdvanceMs(ms) }
    }

    fun setChannelCaptionAdvance(guideId: String, ms: Int) {
        save { store.setChannelCaptionAdvance(guideId, ms) }
    }

    fun setSmoothCaptions(enabled: Boolean) {
        save { store.setSmoothCaptions(enabled) }
    }

    fun setCaptionStyle(style: CaptionStyle) {
        save { store.setCaptionStyle(style) }
    }

    fun setKeyLegendShown(count: Int) {
        save { store.setKeyLegendShown(count) }
    }

    fun setAutoOpenDelay(delay: AutoOpenDelay) {
        save { store.setAutoOpenDelay(delay) }
    }

    fun setPreviewSound(enabled: Boolean) {
        save { store.setPreviewSound(enabled) }
    }

    fun setVolume(level: Int, muted: Boolean) {
        save { store.setVolume(level, muted) }
    }

    fun setStartup(target: StartupTarget, guideId: String? = null) {
        save { store.setStartup(target, guideId) }
    }

    fun setLastTab(tab: TopLevelTab) {
        save { store.setLastTab(tab) }
    }

    fun setClockFormat(format: ClockFormat) {
        save { store.setClockFormat(format) }
    }

    fun setGuideDensity(density: GuideDensity) {
        save { store.setGuideDensity(density) }
    }

    fun setChannelHidden(guideId: String, hidden: Boolean) {
        save { store.setChannelHidden(guideId, hidden) }
    }

    fun setChannelsHidden(guideIds: Collection<String>, hidden: Boolean) {
        save { store.setChannelsHidden(guideIds, hidden) }
    }

    fun setZapSkipsHidden(enabled: Boolean) {
        save { store.setZapSkipsHidden(enabled) }
    }

    fun setSkipCommercials(enabled: Boolean) {
        save { store.setSkipCommercials(enabled) }
    }

    fun setAutoSkipIntros(enabled: Boolean) {
        save { store.setAutoSkipIntros(enabled) }
    }

    fun setHideSpoilers(enabled: Boolean) {
        save { store.setHideSpoilers(enabled) }
    }

    fun setLiveBreaks(mode: LiveBreaks) {
        save { store.setLiveBreaks(mode) }
    }

    /** Counts [ms] more of ads the break screen covered on [day], a local date as `2026-10-22`. */
    fun addAdsDodged(day: String, ms: Long) {
        if (ms > 0) save { store.addAdsDodged(day, ms) }
    }

    /** Keeps the tracks picked in the show [showId] for its next episodes. */
    fun setTrackChoice(showId: String, choice: TrackChoice) {
        save { store.setTrackChoice(showId, choice) }
    }

    fun setFavoritesOrder(order: List<String>) {
        save { store.setFavoritesOrder(order) }
    }

    fun setMatchFrameRate(enabled: Boolean) {
        save { store.setMatchFrameRate(enabled) }
    }

    fun setDecoder(decoder: DecoderPreference) {
        save { store.setDecoder(decoder) }
    }

    fun setChannelDecoder(guideId: String, decoder: DecoderPreference?) {
        save { store.setChannelDecoder(guideId, decoder) }
    }

    fun setBuffer(buffer: BufferPreference) {
        save { store.setBuffer(buffer) }
    }

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
    fun setSettingsPin(pin: String?) {
        save { store.setSettingsPin(pin?.let(SettingsPin::hash)) }
    }

    /** Writes a setting in this ViewModel's scope; a write the store refuses is logged rather than crashing the app. */
    private fun save(write: suspend () -> Unit) {
        viewModelScope.launch(logFailures) { write() }
    }

    /**
     * [live] plus every day in [extra], per channel in start order without repeats, so a day loaded twice reads once.
     */
    private fun mergeGuides(
        live: Map<String, List<Programme>>,
        extra: Collection<Map<String, List<Programme>>>,
    ): Map<String, List<Programme>> {
        val merged = HashMap<String, List<Programme>>(live)
        for (day in extra) {
            for ((channel, programmes) in day) {
                val current = merged[channel]
                merged[channel] =
                    if (current == null) {
                        programmes
                    } else {
                        (current + programmes).distinctBy { it.start }.sortedBy { it.start }
                    }
            }
        }
        return merged
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
        val REFRESH_INTERVAL = 3.hours
        val RETRY_INTERVAL = 1.minutes
        val RELOAD_WAIT = 30.seconds
    }
}
