package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.ByteStore
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LineupCache
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.ServerAddress
import dev.jamesrich.sofaking.data.SettingsStore
import dev.jamesrich.sofaking.data.SofaKingApi
import dev.jamesrich.sofaking.data.SofaKingException
import dev.jamesrich.sofaking.data.SofaKingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

sealed interface LineupState {
    data object Loading : LineupState

    /** No usable server address yet; Settings is where it goes. */
    data object Unconfigured : LineupState
    data class Ready(val lineup: Lineup) : LineupState
    data class Failed(val message: String) : LineupState
}

/** A server address and token the API can be built from. */
private data class Server(val url: String, val token: String)

/**
 * The lineup, the settings and the one playback session, for every screen. [newApi] builds the API client for a
 * server address and token; tests swap it for one aimed at a fake server.
 */
class LineupViewModel(
    private val store: SettingsStore,
    private val cacheStore: ByteStore,
    /** The one playback session the app holds; screens tune through it and the host releases it when stopped. */
    val sessions: PlaybackSessions = PlaybackSessions(),
    private val newApi: (url: String, token: String) -> SofaKingApi = ::SofaKingApi,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _loading = MutableStateFlow(false)

    /** True while the catalog and guide are being fetched, including a background or manual refresh. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Counts of catalogs published and loads finished (either way), so [reloadLineup] can wait for the load it asked for. */
    private val catalogsLoaded = MutableStateFlow(0)
    private val loadsFinished = MutableStateFlow(0)

    /** The last lineup loaded, with the server key it came from; read and written only inside [state]'s transform. */
    private var lastGood: Pair<String, Lineup>? = null

    /** The server as Settings has it, or null while the address is not a usable URL. */
    private val server = store.settings
        .map { s -> ServerAddress.normalizeUrl(s.serverUrl)?.let { Server(it, s.apiToken) } }
        .distinctUntilChanged()

    init {
        // A new address or token releases the session held on the old server before anything tunes on the new one.
        viewModelScope.launch {
            server.collect { sessions.configure(it?.let { s -> newApi(s.url, s.token) }) }
        }
    }

    /** One repository per server, so its catalog ETag survives every refresh; rebuilt only when the server changes. */
    private val serverRepository = server.map { s -> s?.let { it to SofaKingRepository(newApi(it.url, it.token)) } }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LineupState> = combine(serverRepository, reloads.onStart { emit(Unit) }) { found, _ -> found }
        .transformLatest { found ->
            if (found == null) {
                emit(LineupState.Unconfigured)
                return@transformLatest
            }
            val (server, repository) = found
            val key = server.url
            val cache = LineupCache(cacheStore, key)
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
                val result = try {
                    val started = TimeSource.Monotonic.markNow()
                    val channels = repository.loadChannels()
                    log.i { "lineup: ${channels.size} channels in ${started.elapsedNow()}" }
                    // Channels first so the guide is usable while the programmes arrive. A cached guide stays in
                    // place for channels it still covers; it is replaced once the new guide is in.
                    if (last?.channels != channels) emit(LineupState.Ready(Lineup(channels, last?.guide.orEmpty())))
                    catalogsLoaded.value++
                    val guide = repository.loadGuide(channels)
                    log.i { "guide: ${guide.values.sumOf { it.size }} programmes for ${guide.size} channels in ${started.elapsedNow()}" }
                    Result.success(Lineup(channels, guide))
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
                result.onFailure { if (last == null) emit(LineupState.Failed(describe(it))) }
                loadsFinished.value++
                delay(if (result.isSuccess) REFRESH_INTERVAL else RETRY_INTERVAL)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LineupState.Loading)

    fun refresh() {
        reloads.tryEmit(Unit)
    }

    /**
     * Asks for a reload and waits for its catalog: the lineup holding the new channels, or null when the load
     * failed or nothing came back in time. The guide may still be loading when it returns.
     */
    suspend fun reloadLineup(): Lineup? {
        val catalogs = catalogsLoaded.value
        val loads = loadsFinished.value
        refresh()
        val catalogArrived = withTimeoutOrNull(RELOAD_WAIT) {
            combine(catalogsLoaded, loadsFinished) { c, l -> c > catalogs || l > loads }.first { it }
            catalogsLoaded.value > catalogs
        } ?: false
        return if (catalogArrived) (state.value as? LineupState.Ready)?.lineup else null
    }

    fun saveServer(url: String, apiToken: String) = viewModelScope.launch { store.setServer(url, apiToken) }

    fun toggleFavorite(guideId: String) = viewModelScope.launch { store.toggleFavorite(guideId) }

    fun setLastChannel(guideId: String) = viewModelScope.launch { store.setLastChannel(guideId) }

    fun setCaptionsEnabled(enabled: Boolean) = viewModelScope.launch { store.setCaptionsEnabled(enabled) }

    fun setCaptionAdvanceMs(ms: Int) = viewModelScope.launch { store.setCaptionAdvanceMs(ms) }

    fun setChannelCaptionAdvance(guideId: String, ms: Int) =
        viewModelScope.launch { store.setChannelCaptionAdvance(guideId, ms) }

    fun setSmoothCaptions(enabled: Boolean) = viewModelScope.launch { store.setSmoothCaptions(enabled) }

    fun setCaptionStyle(style: CaptionStyle) = viewModelScope.launch { store.setCaptionStyle(style) }

    fun setAutoOpenDelay(delay: AutoOpenDelay) = viewModelScope.launch { store.setAutoOpenDelay(delay) }

    fun setPreviewSound(enabled: Boolean) = viewModelScope.launch { store.setPreviewSound(enabled) }

    override fun onCleared() {
        sessions.close()
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
