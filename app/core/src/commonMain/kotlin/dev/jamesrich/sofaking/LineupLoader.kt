package dev.jamesrich.sofaking

import androidx.datastore.core.DataStore
import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.CachedLineup
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LineupCache
import dev.jamesrich.sofaking.data.Server
import dev.jamesrich.sofaking.data.SofaKingRepository
import dev.jamesrich.sofaking.data.describe
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Loads a server's lineup into the [LineupState] flow it collects in: the cache first, then the network every
 * [REFRESH_INTERVAL], retrying a failure within seconds. [lineupCache] keeps the last good lineup per server, [clock]
 * dates the guide window and the cache, [background] decodes off the main thread, and [days] loses the extra days a
 * fresh lineup covers.
 */
internal class LineupLoader(
    private val lineupCache: DataStore<CachedLineup?>,
    private val clock: Clock,
    private val background: CoroutineDispatcher,
    private val days: GuideDays,
) {
    private val busy = MutableStateFlow(false)

    /** True while the catalog and guide are being fetched, including a background or manual refresh. */
    val loading: StateFlow<Boolean> = busy.asStateFlow()

    /** The last lineup loaded, with the server key it came from; read and written only inside [load]. */
    private var lastGood: Pair<String, Lineup>? = null

    /** The repository of the lineup on screen, for an extra day's query; set by [load]. */
    var repository: SofaKingRepository? = null
        private set

    /**
     * Loads [found]'s lineup, cache first, then from the network every [REFRESH_INTERVAL]; [onCatalog] gets the first
     * load's catalog, or null when it fails.
     */
    suspend fun FlowCollector<LineupState>.load(
        found: Pair<Server, SofaKingRepository>?,
        onCatalog: (Lineup?) -> Unit,
    ) {
        if (found == null) {
            emit(LineupState.Unconfigured)
            return
        }
        val (server, repository) = found
        val key = server.url
        this@LineupLoader.repository = repository
        val cache = LineupCache(lineupCache, key, background, clock::now)
        // A refresh keeps the lineup already on screen; a cold start or a server change reads the cache,
        // so the last good lineup shows at once and the network load replaces it below.
        var last: Lineup? = lastGood?.takeIf { it.first == key }?.second ?: cached(cache, key)
        var failures = 0
        while (true) {
            if (last == null) emit(LineupState.Loading)
            val attempt = LoadAttempt()
            // Cancellation (a server change or refresh while loading) must propagate, not read as a failure.
            busy.value = true
            val result = try {
                runCatchingCancellable { fetch(repository, last, attempt, onCatalog) }
            } finally {
                // Also on cancellation, or a refresh or server change mid-load leaves the loader spinning.
                busy.value = false
            }
            result.onSuccess {
                last = it
                loaded(it, key, cache)
            }
            // A failed background refresh keeps showing the lineup we already have.
            result.onFailure {
                onCatalog(null)
                last = failed(it, attempt.interim, last)
            }
            failures = if (result.isSuccess) 0 else failures + 1
            delay(nextLoad(failures, attempt.lineupMoved))
        }
    }

    /**
     * [cache]'s lineup for [key], or null when it has none. Its channels show first, so a channel or a tab opens while
     * the programs decode, which takes seconds on a TV.
     */
    private suspend fun FlowCollector<LineupState>.cached(cache: LineupCache, key: String): Lineup? {
        val cacheStarted = TimeSource.Monotonic.markNow()
        val cached = cache.open() ?: return null
        log.i { "cache: ${cached.channels.size} channels in ${cacheStarted.elapsedNow()}" }
        emit(LineupState.Ready(Lineup(cached.channels, emptyMap(), guideLoading = true)))
        // A guide that failed to decode, or whose programs have all ended, is still on its way from the server.
        val guide = cached.guide()
        val full = Lineup(cached.channels, guide.orEmpty(), guideLoading = guide.isNullOrEmpty())
        log.i { "cache: ${full.guide.values.sumOf { it.size }} programs in ${cacheStarted.elapsedNow()}" }
        lastGood = key to full
        emit(LineupState.Ready(full))
        return full
    }

    /**
     * Loads [repository]'s catalog, then its guide, over [last]; [attempt] keeps the channels shown on the way and
     * whether the lineup moved meanwhile, and [onCatalog] gets the catalog as soon as it is in.
     */
    private suspend fun FlowCollector<LineupState>.fetch(
        repository: SofaKingRepository,
        last: Lineup?,
        attempt: LoadAttempt,
        onCatalog: (Lineup?) -> Unit,
    ): Lineup {
        val started = TimeSource.Monotonic.markNow()
        val channels = repository.loadChannels()
        log.i { "lineup: ${channels.size} channels in ${started.elapsedNow()}" }
        // Channels first so the guide is usable while the programs arrive. A cached guide stays in place for
        // channels it still covers; it is replaced once the new guide is in.
        if (last?.channels != channels) {
            val interim = Lineup(channels, last?.guide.orEmpty(), guideLoading = true)
            attempt.interim = interim
            emit(LineupState.Ready(interim))
        }
        onCatalog(attempt.interim ?: last)
        val guide = repository.loadGuide(channels)
        log.i {
            "guide: ${guide.programs.values.sumOf { it.size }} programs for ${guide.programs.size} " +
                "channels in ${started.elapsedNow()}"
        }
        // The lineup changed between the catalog and the guide: drop what left and read the catalog again soon.
        attempt.lineupMoved = guide.removed.isNotEmpty()
        if (attempt.lineupMoved) log.i { "guide: ${guide.removed.size} channels left the lineup" }
        return Lineup(channels.filterNot { it.guideId in guide.removed }, guide.programs)
    }

    /** Shows [lineup], just loaded from the server [key] names, drops the extra days it covers, and caches it. */
    private suspend fun FlowCollector<LineupState>.loaded(lineup: Lineup, key: String, cache: LineupCache) {
        lastGood = key to lineup
        emit(LineupState.Ready(lineup))
        days.dropPast()
        // A cache write failure must not fail the load or delay the next refresh.
        runCatchingCancellable {
            cache.write(lineup)
        }.onFailure { e ->
            log.w(e) { "lineup cache write failed" }
        }
    }

    /**
     * Shows what stays after a load failed with [e]: the [interim] channels it got, [last] without its guide
     * spinner, or the failure itself when there is nothing to show. Returns the lineup now on screen.
     */
    private suspend fun FlowCollector<LineupState>.failed(e: Throwable, interim: Lineup?, last: Lineup?): Lineup? {
        val kept = when {
            // The new channels stay on screen without programs until a retry brings the guide; they are not cached,
            // so a cold start still shows the last complete lineup.
            interim != null -> interim.copy(guideLoading = false)

            last?.guideLoading == true -> last.copy(guideLoading = false)

            else -> null
        }
        if (kept != null) {
            emit(LineupState.Ready(kept))
        } else if (last == null) {
            emit(LineupState.Failed(e.describe()))
        }
        return kept ?: last
    }

    /**
     * How long until the next load. After [failures] in a row it tries again within seconds, then less often: a
     * server only slow to answer at startup is back before the viewer reaches for Try again.
     */
    private fun nextLoad(failures: Int, lineupMoved: Boolean): Duration = when {
        failures > 0 -> minOf(FIRST_RETRY * (1 shl minOf(failures - 1, RETRY_DOUBLINGS)), RETRY_INTERVAL)
        lineupMoved -> RETRY_INTERVAL
        else -> REFRESH_INTERVAL
    }

    /** What one load showed on its way: the new channels before their guide, and whether the lineup moved. */
    private class LoadAttempt {
        var interim: Lineup? = null
        var lineupMoved = false
    }

    private companion object {
        val log = Logger.withTag("SofaKing")
        val REFRESH_INTERVAL = 3.hours
        val RETRY_INTERVAL = 1.minutes
        val FIRST_RETRY = 5.seconds

        /** Doublings of [FIRST_RETRY] past [RETRY_INTERVAL], so the wait stops growing well before it overflows. */
        const val RETRY_DOUBLINGS = 4
    }
}
