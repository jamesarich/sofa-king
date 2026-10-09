package dev.jamesrich.sofaking

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.DispatcharrRepository
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LineupCache
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.SettingsStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

sealed interface LineupState {
    data object Loading : LineupState
    data class Ready(val lineup: Lineup) : LineupState
    data class Failed(val message: String) : LineupState
}

class LineupViewModel(application: Application) : AndroidViewModel(application) {
    private val store: SettingsStore = DataStoreSettingsStore.create(
        application,
        AppSettings(BuildConfig.DISPATCHARR_URL, BuildConfig.DISPATCHARR_PROFILE),
    )

    val settings: StateFlow<AppSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val cacheFile = File(application.filesDir, "lineup-cache.bin")

    /** The last lineup loaded, with the server key it came from; read and written only inside [state]'s transform. */
    private var lastGood: Pair<String, Lineup>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LineupState> = combine(
        store.settings.map { it.serverUrl to it.profile }.distinctUntilChanged(),
        reloads.onStart { emit(Unit) },
    ) { server, _ -> server }
        .transformLatest { (url, profile) ->
            val key = "$url\n$profile"
            val repository = DispatcharrRepository(url, profile)
            val cache = LineupCache(cacheFile, key)
            // A refresh keeps the lineup already on screen; a cold start or a server change reads the cache,
            // so the last good lineup shows at once and the network load replaces it below.
            var last: Lineup? = lastGood?.takeIf { it.first == key }?.second
            if (last == null) {
                val cacheStarted = TimeSource.Monotonic.markNow()
                cache.read()?.let { cached ->
                    Log.i(TAG, "cache: ${cached.guide.values.sumOf { it.size }} programmes in ${cacheStarted.elapsedNow()}")
                    last = cached
                    lastGood = key to cached
                    emit(LineupState.Ready(cached))
                }
            }
            while (true) {
                if (last == null) emit(LineupState.Loading)
                // Cancellation (a server change or refresh while loading) must propagate, not read as a failure.
                val result = try {
                    val started = TimeSource.Monotonic.markNow()
                    val channels = repository.loadChannels()
                    Log.i(TAG, "lineup: ${channels.size} channels in ${started.elapsedNow()}")
                    // Channels first so the guide is usable while the large XMLTV parses. A cached guide
                    // stays in place for channels it still covers; it is replaced once the new guide parses.
                    if (last?.channels != channels) emit(LineupState.Ready(Lineup(channels, last?.guide.orEmpty())))
                    val guide = repository.loadGuide(channels)
                    Log.i(TAG, "guide: ${guide.values.sumOf { it.size }} programmes for ${guide.size} channels in ${started.elapsedNow()}")
                    Result.success(Lineup(channels, guide))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
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
                        Log.w(TAG, "lineup cache write failed", e)
                    }
                }
                // A failed background refresh keeps showing the lineup we already have.
                result.onFailure { if (last == null) emit(LineupState.Failed(it.message ?: it.toString())) }
                delay(if (result.isSuccess) REFRESH_INTERVAL else RETRY_INTERVAL)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LineupState.Loading)

    fun refresh() {
        reloads.tryEmit(Unit)
    }

    fun saveServer(url: String, profile: String) = viewModelScope.launch { store.setServer(url, profile) }

    fun toggleFavorite(guideId: String) = viewModelScope.launch { store.toggleFavorite(guideId) }

    fun setLastChannel(guideId: String) = viewModelScope.launch { store.setLastChannel(guideId) }

    fun setCaptionsEnabled(enabled: Boolean) = viewModelScope.launch { store.setCaptionsEnabled(enabled) }

    fun setCaptionAdvanceMs(ms: Int) = viewModelScope.launch { store.setCaptionAdvanceMs(ms) }

    fun setChannelCaptionAdvance(guideId: String, ms: Int) =
        viewModelScope.launch { store.setChannelCaptionAdvance(guideId, ms) }

    fun setSmoothCaptions(enabled: Boolean) = viewModelScope.launch { store.setSmoothCaptions(enabled) }

    fun setCaptionStyle(style: CaptionStyle) = viewModelScope.launch { store.setCaptionStyle(style) }

    private companion object {
        const val TAG = "SofaKing"
        val REFRESH_INTERVAL = 3.hours
        val RETRY_INTERVAL = 1.minutes
    }
}
