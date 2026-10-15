package dev.jamesrich.sofaking

import androidx.lifecycle.viewModelScope
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.CachedLineup
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.FakeHttp
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LineupCache
import dev.jamesrich.sofaking.data.MemoryDataStore
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.SettingsStore
import dev.jamesrich.sofaking.data.apiError
import dev.jamesrich.sofaking.data.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LineupViewModelTest {
    private val now = Instant.parse("2026-10-10T10:17:42Z")

    /** Settings in memory; only the server and the last channel matter here. */
    private class FakeSettings(initial: AppSettings) : SettingsStore {
        override val settings = MutableStateFlow(initial)
        override suspend fun setServer(serverUrl: String, apiToken: String) = settings.update { it.copy(serverUrl = serverUrl, apiToken = apiToken) }
        override suspend fun toggleFavorite(guideId: String) = settings.update { it.copy(favorites = it.favorites xor guideId) }
        override suspend fun setLastChannel(guideId: String) = settings.update { it.copy(lastChannel = guideId) }
        override suspend fun setCaptionsEnabled(enabled: Boolean) = settings.update { it.copy(captionsEnabled = enabled) }
        override suspend fun setCaptionAdvanceMs(ms: Int) = settings.update { it.copy(captionAdvanceMs = ms) }
        override suspend fun setChannelCaptionAdvance(guideId: String, ms: Int) = settings.update { it.copy(captionAdvanceByChannel = it.captionAdvanceByChannel + (guideId to ms)) }
        override suspend fun setSmoothCaptions(enabled: Boolean) = settings.update { it.copy(smoothCaptions = enabled) }
        override suspend fun setCaptionStyle(style: CaptionStyle) = settings.update { it.copy(captionStyle = style) }
        override suspend fun setAutoOpenDelay(delay: AutoOpenDelay) = settings.update { it.copy(autoOpenDelay = delay) }
        override suspend fun setPreviewSound(enabled: Boolean) = settings.update { it.copy(previewSound = enabled) }
        override suspend fun setKeyLegendShown(count: Int) = settings.update { it.copy(keyLegendShown = count) }

        private infix fun Set<String>.xor(id: String) = if (id in this) this - id else this + id
    }

    /** A server with a catalog of [channelIds], a one-programme guide per channel, and switches to fail either. */
    private class FakeServer(scope: TestScope) {
        var channelIds = listOf("c1", "c2")
        var failCatalog = false
        var failGuide = false
        val removed = mutableSetOf<String>()
        var catalogRequests = 0

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
                    val rows = channelIds.joinToString(",") { id ->
                        if (id in removed) """{"channelId":"$id","status":"removed","programmes":[]}"""
                        else """{"channelId":"$id","status":"available","programmes":[{"id":"p-$id","channelId":"$id","startsAt":"2026-10-10T10:00:00Z","endsAt":"2026-10-10T11:00:00Z","title":"Show $id"}]}"""
                    }
                    json("""{"revision":"r","from":"x","to":"y","channels":[$rows]}""")
                }
                else -> apiError(404, "RESOURCE_NOT_FOUND")
            }
        }
    }

    private val cacheStore = MemoryDataStore<CachedLineup?>(null)
    private val settings = FakeSettings(AppSettings(serverUrl = "http://sofa.test", apiToken = "t"))
    private var viewModel: LineupViewModel? = null

    private fun TestScope.start(server: FakeServer): LineupViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return LineupViewModel(
            store = settings,
            lineupCache = cacheStore,
            sessions = PlaybackSessions(scope = backgroundScope),
            newApi = { url, token -> server.http.api(token, "$url/") },
        ).also { viewModel = it }
    }

    @AfterTest
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private val LineupState.lineup get() = assertIs<LineupState.Ready>(this).lineup

    /**
     * [runTest] with the ViewModel stopped as the body ends: its refresh loop runs on virtual time, where the
     * three-hour delay costs nothing, so a scope left running refreshes without end while the test drains.
     */
    private fun viewModelTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            viewModel?.viewModelScope?.cancel()
        }
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

        assertEquals(cached, viewModel.state.first { it is LineupState.Ready }.lineup)
        val fresh = viewModel.state.first { it is LineupState.Ready && it.lineup.channels.first().guideId == "c1" }.lineup
        assertEquals(listOf("c1", "c2"), fresh.channels.map { it.guideId })
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
        settings.settings.value = AppSettings()
        val server = FakeServer(this)
        val viewModel = start(server)

        assertEquals(LineupState.Unconfigured, viewModel.state.first { it != LineupState.Loading })

        viewModel.saveServer("http://sofa.test", "t")
        assertEquals(2, viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }.lineup.channels.size)
    }
}
