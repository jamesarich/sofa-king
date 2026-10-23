package dev.jamesrich.sofaking

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.jamesrich.sofaking.data.CachedLineup
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.DataStoreSettingsStore
import dev.jamesrich.sofaking.data.FakeHttp
import dev.jamesrich.sofaking.data.GuideWindow
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LineupCache
import dev.jamesrich.sofaking.data.MemoryDataStore
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.ServerCapabilities
import dev.jamesrich.sofaking.data.ServerConnection
import dev.jamesrich.sofaking.data.SettingsStore
import dev.jamesrich.sofaking.data.apiError
import dev.jamesrich.sofaking.data.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val NOW = "2026-10-10T10:17:42Z"

@OptIn(ExperimentalCoroutinesApi::class)
class LineupViewModelTest {
    private val now = Instant.parse("2026-10-10T10:17:42Z")

    /** The wall clock the ViewModel reads, stopped at [now], so the guide window never moves under a test. */
    private val clock = object : Clock {
        override fun now() = now
    }

    /** A server with a catalog of [channelIds], a one-program guide per channel, and switches to fail either. */
    private class FakeServer(scope: TestScope) {
        var channelIds = listOf("c1", "c2")
        var failCatalog = false
        var failGuide = false
        val removed = mutableSetOf<String>()
        var catalogRequests = 0
        var guideRequests = 0
        /** The bootstrap's capabilities; null answers 404, as a server from before the route. */
        var capabilities: String? = null

        val http = FakeHttp(scope) { request ->
            when (request.path) {
                "/api/v1/channels" -> {
                    catalogRequests++
                    if (failCatalog) return@FakeHttp apiError(503, "NOT_READY")
                    val channels = channelIds.mapIndexed { i, id -> """{"id":"$id","displayNumber":"${i + 1}","name":"Channel $id","playbackEligible":true}""" }
                    json("""{"revision":"r","channels":[${channels.joinToString(",")}]}""")
                }
                "/api/v1/guide/query" -> {
                    if (failGuide) return@FakeHttp apiError(503, "NOT_READY")
                    guideRequests++
                    // One program per channel at the start of the window asked for, so a second day reads apart from the first.
                    val from = Regex(""""from":"([^"]+)"""").find(request.body)?.groupValues?.get(1)
                    val start = from?.let { Instant.parse(it) } ?: Instant.parse("2026-10-10T10:00:00Z")
                    val rows = channelIds.joinToString(",") { id ->
                        if (id in removed) """{"channelId":"$id","status":"removed","programs":[]}"""
                        else """{"channelId":"$id","status":"available","programs":[{"id":"p-$id","channelId":"$id","startsAt":"$start","endsAt":"${start + 1.hours}","title":"Show $id"}]}"""
                    }
                    json("""{"revision":"r","from":"x","to":"y","channels":[$rows]}""")
                }
                "/api/v1/pair" -> {
                    if (request.headers["Authorization"] != null) return@FakeHttp apiError(500, "TOKEN_SENT")
                    if (request.body.contains(""""code":"654321"""")) json("""{"apiToken":"issued-token","serverId":"srv"}""")
                    else apiError(403, "PAIRING_INVALID")
                }
                "/api/v1/bootstrap" -> capabilities?.let { json("""{"serverId":"srv","serverTime":"$NOW","capabilities":$it}""") }
                    ?: apiError(404, "RESOURCE_NOT_FOUND")
                else -> apiError(404, "RESOURCE_NOT_FOUND")
            }
        }
    }

    private val cacheStore = MemoryDataStore<CachedLineup?>(null)
    private val settings = DataStoreSettingsStore(MemoryDataStore(emptyPreferences()))
    private val fixture = ViewModelFixture()

    @AfterTest
    fun tearDown() = fixture.tearDown()

    /** The ViewModel over [server], with the server saved in Settings unless [configured] is false. */
    private suspend fun TestScope.start(server: FakeServer, configured: Boolean = true, store: SettingsStore = settings): LineupViewModel {
        if (configured) store.setServer("http://sofa.test", "t")
        return fixture.started(
            LineupViewModel(
                store = store,
                lineupCache = cacheStore,
                connection = ServerConnection(
                    settings = store.settings,
                    scope = backgroundScope,
                    sessions = PlaybackSessions(scope = backgroundScope),
                    newApi = { url, token -> server.http.api(token, "$url/") },
                ),
                clock = clock,
                background = StandardTestDispatcher(testScheduler),
            ),
        )
    }

    private val LineupState.lineup get() = assertIs<LineupState.Ready>(this).lineup

    private fun viewModelTest(block: suspend TestScope.() -> Unit) = fixture.viewModelTest(block)

    @Test
    fun `the server capabilities come from its bootstrap and an older server offers none`() = viewModelTest {
        val server = FakeServer(this).apply { capabilities = """{"catalog":true,"recordings":true,"library":true}""" }
        val viewModel = start(server)
        runCurrent()
        assertEquals(ServerCapabilities(recordings = true, library = true), viewModel.capabilities.value)

        server.capabilities = null
        settings.setServer("http://sofa.test/other", "t")
        runCurrent()
        assertEquals(ServerCapabilities(), viewModel.capabilities.value)
    }

    @Test
    fun `a cold start loads the channels then the guide and caches the lineup`() = viewModelTest {
        val server = FakeServer(this)
        val viewModel = start(server)

        assertEquals(LineupState.Loading, viewModel.state.value)
        val lineup = viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }.lineup

        assertEquals(listOf("c1", "c2"), lineup.channels.map { it.guideId })
        assertEquals("Show c1", lineup.guide.getValue("c1").single().title)
        assertEquals(listOf("c1", "c2"), assertNotNull(cacheStore.value).channels.map { it.guideId })
    }

    @Test
    fun `the channels show while the guide loads`() = viewModelTest {
        val server = FakeServer(this)
        server.failGuide = true
        val viewModel = start(server)

        val interim = viewModel.state.first { it is LineupState.Ready }.lineup
        assertTrue(interim.guideLoading)
        assertEquals(2, interim.channels.size)
        assertTrue(interim.guide.isEmpty())

        // The guide failed: the channels stay, no longer marked as loading.
        val settled = viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }.lineup
        assertEquals(2, settled.channels.size)
    }

    @Test
    fun `a cached lineup shows before the network answers and is then replaced`() = viewModelTest {
        val cached = Lineup(listOf(Channel("old", 9.0, "Old", null, listOf("Other"))), emptyMap())
        LineupCache(cacheStore, "http://sofa.test") { now }.write(cached)
        val server = FakeServer(this)
        val viewModel = start(server)

        // The channels show first, while the cached programs decode.
        assertEquals(cached.channels, viewModel.state.first { it is LineupState.Ready }.lineup.channels)
        val fresh = viewModel.state.first { it is LineupState.Ready && it.lineup.channels.first().guideId == "c1" }.lineup
        assertEquals(listOf("c1", "c2"), fresh.channels.map { it.guideId })
    }

    @Test
    fun `a cached lineup without programs still on is loading until the network answers`() = viewModelTest {
        val cached = Lineup(listOf(Channel("old", 9.0, "Old", null, listOf("Other"))), emptyMap())
        LineupCache(cacheStore, "http://sofa.test") { now }.write(cached)
        val server = FakeServer(this)
        server.failCatalog = true
        val viewModel = start(server)

        assertTrue(viewModel.state.first { it is LineupState.Ready }.lineup.guideLoading, "the rows read loading, not no listing")
        val settled = viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }.lineup
        assertEquals(cached.channels, settled.channels, "a failed load stops loading and keeps the cached channels")
    }

    @Test
    fun `a failed refresh keeps the lineup on screen and a failed first load does not`() = viewModelTest {
        val server = FakeServer(this)
        server.failCatalog = true
        val viewModel = start(server)

        assertIs<LineupState.Failed>(viewModel.state.first { it is LineupState.Failed })

        server.failCatalog = false
        advanceTimeBy(1.minutes + 1.minutes)
        val loaded = viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }.lineup

        server.failCatalog = true
        viewModel.refresh()
        runCurrent()
        assertEquals(loaded, viewModel.state.value.lineup)
    }

    @Test
    fun `the lineup refreshes every three hours and drops channels the guide reports removed`() = viewModelTest {
        val server = FakeServer(this)
        val viewModel = start(server)
        viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }
        assertEquals(1, server.catalogRequests)

        server.removed += "c2"
        advanceTimeBy(3.hours + 1.minutes)
        assertEquals(2, server.catalogRequests)
        assertEquals(listOf("c1"), viewModel.state.value.lineup.channels.map { it.guideId })
    }

    @Test
    fun `a day ahead is loaded once and merged into the guide until the live window passes it`() = viewModelTest {
        val server = FakeServer(this)
        val viewModel = start(server)
        viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }
        val live = GuideWindow.start(now)
        val tomorrow = live + 24.hours

        viewModel.loadGuideWindow(tomorrow)
        viewModel.loadGuideWindow(tomorrow)
        val lineup = viewModel.state.first { it is LineupState.Ready && it.lineup.guide.getValue("c1").size == 2 }.lineup
        assertEquals(listOf(live, tomorrow), lineup.guide.getValue("c1").map { it.start })
        assertEquals(2, server.guideRequests)

        // The live window itself is never an extra day, and a loaded day is not asked for again.
        viewModel.loadGuideWindow(live)
        viewModel.loadGuideWindow(tomorrow)
        runCurrent()
        assertEquals(2, server.guideRequests)

        // A refresh keeps the extra day beside the fresh live guide.
        advanceTimeBy(3.hours + 1.minutes)
        assertEquals(2, viewModel.state.value.lineup.guide.getValue("c1").size)

        // So does a reload asked for by hand, which nothing would follow by asking for the day again.
        viewModel.refresh()
        runCurrent()
        assertEquals(2, viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }.lineup.guide.getValue("c1").size)
    }

    @Test
    fun `reloadLineup answers with the catalog of the reload it asked for`() = viewModelTest {
        val server = FakeServer(this)
        val viewModel = start(server)
        viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }

        server.channelIds = listOf("c1", "c2", "c3")
        val reloaded = assertNotNull(viewModel.reloadLineup())
        assertEquals(listOf("c1", "c2", "c3"), reloaded.channels.map { it.guideId })

        server.failCatalog = true
        assertNull(viewModel.reloadLineup())
    }

    @Test
    fun `no usable server address is unconfigured and saving one loads the lineup`() = viewModelTest {
        val server = FakeServer(this)
        val viewModel = start(server, configured = false)

        assertEquals(LineupState.Unconfigured, viewModel.state.first { it != LineupState.Loading })

        viewModel.saveServer("http://sofa.test", "t")
        assertEquals(2, viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }.lineup.channels.size)
    }

    @Test
    fun `pairing saves the server and the token it hands out while a refused code saves nothing`() = viewModelTest {
        val server = FakeServer(this)
        val viewModel = start(server, configured = false)
        assertEquals(LineupState.Unconfigured, viewModel.state.first { it != LineupState.Loading })

        assertEquals(PairingFailure.INVALID_CODE, viewModel.pairAndWait("http://sofa.test", "111 111"))
        assertEquals("", settings.settings.first().serverUrl)
        assertEquals(PairingFailure.INVALID_CODE, viewModel.pairAndWait("http://sofa.test", "12"), "a malformed code is not even sent")
        assertEquals(1, server.http.requests.count { it.path == "/api/v1/pair" })

        assertNull(viewModel.pairAndWait("http://sofa.test/", "654 321"))
        assertEquals("http://sofa.test", settings.settings.first().serverUrl)
        assertEquals("issued-token", settings.settings.first().apiToken)
        assertTrue(server.http.requests.last { it.path == "/api/v1/pair" }.body.contains(""""clientId":"${settings.settings.first().clientId}""""))
        assertIs<LineupState.Ready>(viewModel.state.first { it is LineupState.Ready })
        assertEquals("Bearer issued-token", server.http.requests.last { it.path == "/api/v1/channels" }.headers["Authorization"])
    }

    @Test
    fun `pairing reports its outcome as state until the screen has acted on it`() = viewModelTest {
        val server = FakeServer(this)
        val viewModel = start(server, configured = false)
        assertEquals(LineupState.Unconfigured, viewModel.state.first { it != LineupState.Loading })

        viewModel.pair("http://sofa.test", "111 111")
        assertTrue(viewModel.pairing.value.busy)
        viewModel.pair("http://sofa.test", "654 321")
        assertEquals(PairingState(failure = PairingFailure.INVALID_CODE, outcome = PairingOutcome.FAILED), viewModel.pairing.first { !it.busy })
        assertEquals(1, server.http.requests.count { it.path == "/api/v1/pair" }, "a second code while one is claimed is ignored")
        viewModel.pairingHandled()
        assertEquals(PairingState(failure = PairingFailure.INVALID_CODE), viewModel.pairing.value)

        viewModel.pair("http://sofa.test", "654 321")
        assertEquals(PairingState(outcome = PairingOutcome.PAIRED), viewModel.pairing.first { !it.busy })
        assertEquals("issued-token", settings.settings.first().apiToken)
    }

    @Test
    fun `a settings write the store refuses is logged and the app carries on`() = viewModelTest {
        val refusing = object : DataStore<Preferences> {
            override val data = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
        }
        val viewModel = start(FakeServer(this), configured = false, store = DataStoreSettingsStore(refusing))

        viewModel.setCaptionsEnabled(true)
        viewModel.toggleFavorite("c1")
        runCurrent()
        assertEquals(LineupState.Unconfigured, viewModel.state.value)
    }
}
