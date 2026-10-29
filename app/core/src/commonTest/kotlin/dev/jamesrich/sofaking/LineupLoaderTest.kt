package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.CachedLineup
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.FakeHttp
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LineupCache
import dev.jamesrich.sofaking.data.MemoryDataStore
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.Server
import dev.jamesrich.sofaking.data.SofaKingRepository
import dev.jamesrich.sofaking.data.apiError
import dev.jamesrich.sofaking.data.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LineupLoaderTest {
    private val now = Instant.parse("2026-10-10T10:17:42Z")
    private val clock = object : Clock {
        override fun now() = now
    }

    /** Holds every task sent to it until [release], standing in for a cache decode that takes seconds. */
    private class HeldDispatcher(private val real: CoroutineDispatcher) : CoroutineDispatcher() {
        private val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        private var open = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (open) real.dispatch(context, block) else held += context to block
        }

        fun release() {
            open = true
            held.forEach { (context, block) -> real.dispatch(context, block) }
            held.clear()
        }
    }

    private class Run(val http: FakeHttp, val decode: HeldDispatcher, val states: List<LineupState>) {
        /** The titles of the programs each lineup shown carried, in the order they were shown. */
        val titles get() = states.filterIsInstance<LineupState.Ready>().map { ready ->
            ready.lineup.guide.values.flatten().map { it.title }
        }
    }

    /** A loader over a cache holding one channel with a "Cached" program, whose decode waits for [Run.decode]. */
    private suspend fun TestScope.start(failCatalog: Boolean = false): Run {
        val store = MemoryDataStore<CachedLineup?>(null)
        val program = Program("c1", now - 10.minutes, now + 50.minutes, "Cached", null)
        val channel = Channel("c1", 1.0, "Channel c1", null, listOf("All"))
        LineupCache(store, "http://sofa.test") { now }.write(Lineup(listOf(channel), mapOf("c1" to listOf(program))))
        val decode = HeldDispatcher(StandardTestDispatcher(testScheduler))
        val http = FakeHttp(this) { request ->
            when (request.path) {
                "/api/v1/channels" -> if (failCatalog) {
                    apiError(503, "NOT_READY")
                } else {
                    json(
                        """{"revision":"r","channels":[{"id":"c1","displayNumber":"1","name":"Channel c1",""" +
                            """"playbackEligible":true}]}""",
                    )
                }

                "/api/v1/guide/query" -> json(
                    """{"revision":"r","from":"x","to":"y","channels":[{"channelId":"c1","status":"available",""" +
                        """"programs":[{"id":"p","channelId":"c1","startsAt":"2026-10-10T10:00:00Z",""" +
                        """"endsAt":"2026-10-10T11:00:00Z","title":"Fresh"}]}]}""",
                )

                else -> apiError(404, "RESOURCE_NOT_FOUND")
            }
        }
        val states = mutableListOf<LineupState>()
        val loader = LineupLoader(store, clock, decode, GuideDays(clock, backgroundScope))
        val repository = SofaKingRepository(http.api(), StandardTestDispatcher(testScheduler)) { now }
        val server = Server("http://sofa.test", "t", http.api())
        backgroundScope.launch {
            flow { with(loader) { load(server to repository) {} } }.toList(states)
        }
        return Run(http, decode, states)
    }

    @Test
    fun `the first fetch starts while the cached programs are still decoding`() = runTest {
        val run = start()
        runCurrent()

        assertTrue(run.http.requests.any { it.path == "/api/v1/channels" }, "the catalog was asked for")
        assertTrue(run.titles.none { "Cached" in it }, "the cached programs are still held")
    }

    @Test
    fun `a fresh guide wins and the cached programs never show after it`() = runTest {
        val run = start()
        runCurrent()
        assertEquals(listOf("Fresh"), run.titles.last())

        val shown = run.states.size
        run.decode.release()
        runCurrent()

        assertEquals(shown, run.states.size, "the late decode emits nothing")
        assertTrue(run.titles.none { "Cached" in it })
    }

    @Test
    fun `the cached programs show when the fetch fails`() = runTest {
        val run = start(failCatalog = true)
        runCurrent()
        run.decode.release()
        runCurrent()

        val last = (run.states.last() as LineupState.Ready).lineup
        assertEquals(listOf("Cached"), last.guide.values.flatten().map { it.title })
        assertEquals(false, last.guideLoading)
    }
}
