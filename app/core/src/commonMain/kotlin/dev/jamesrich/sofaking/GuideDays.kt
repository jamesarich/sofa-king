package dev.jamesrich.sofaking

import co.touchlab.kermit.Logger
import dev.jamesrich.sofaking.data.GuideWindow
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.SofaKingRepository
import dev.jamesrich.sofaking.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

private val log = Logger.withTag("SofaKing")

/**
 * Guide windows beyond the live one that the guide jumped to, by their start: loaded once each, in [scope], and
 * merged into the lineup until the live window passes them. Never cached; a cold start shows today's guide.
 */
internal class GuideDays(private val clock: Clock, private val scope: CoroutineScope) {
    private val days = MutableStateFlow<Map<Instant, Map<String, List<Program>>>>(emptyMap())

    /** The programs of each extra day, by its window's start. */
    val loaded: StateFlow<Map<Instant, Map<String, List<Program>>>> = days.asStateFlow()

    /** The windows being fetched, so a repeated jump does not query twice; read and written on the main thread. */
    private val loading = HashSet<Instant>()

    /** The server the days were loaded from. */
    private var server: String? = null

    /** A new server starts without the old one's extra days; a reload keeps them, since nothing asks again. */
    fun follow(serverKey: String?) {
        if (serverKey == server) return
        days.value = emptyMap()
        server = serverKey
    }

    /** Drops the days the live window has reached. */
    fun dropPast() {
        val live = GuideWindow.start(clock.now())
        days.update { loaded -> loaded.filterKeys { start -> start > live } }
    }

    /**
     * Loads the programs of the guide window starting at [windowStart] for [lineup]'s channels from [repository], when
     * it lies beyond the live window and is not loaded yet. A failure leaves the window empty, and the next jump to it
     * asks again.
     */
    fun load(windowStart: Instant, lineup: Lineup?, repository: SofaKingRepository?) {
        val live = GuideWindow.start(clock.now())
        if (windowStart <= live || lineup == null || repository == null) return
        if (windowStart in days.value || !loading.add(windowStart)) return
        val from = server
        scope.launch {
            try {
                runCatchingCancellable {
                    val started = TimeSource.Monotonic.markNow()
                    val guide = repository.loadGuide(lineup.channels, windowStart)
                    log.i {
                        "guide: day $windowStart ${guide.programs.values.sumOf { it.size }} programs in " +
                            "${started.elapsedNow()}"
                    }
                    // A day that arrives after Settings named another server is the old server's, so it is dropped.
                    if (server == from) days.update { it + (windowStart to guide.programs) }
                }.onFailure { log.w(it) { "guide: day $windowStart failed" } }
            } finally {
                loading.remove(windowStart)
            }
        }
    }
}

/** [live] plus every day in [extra], per channel in start order without repeats, so a day loaded twice reads once. */
internal fun mergeGuides(
    live: Map<String, List<Program>>,
    extra: Collection<Map<String, List<Program>>>,
): Map<String, List<Program>> {
    val merged = HashMap<String, List<Program>>(live)
    for (day in extra) {
        for ((channel, programs) in day) {
            val current = merged[channel]
            merged[channel] =
                if (current == null) {
                    programs
                } else {
                    (current + programs).distinctBy { it.start }.sortedBy { it.start }
                }
        }
    }
    return merged
}
