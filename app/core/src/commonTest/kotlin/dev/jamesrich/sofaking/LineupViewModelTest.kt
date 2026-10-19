package dev.jamesrich.sofaking

import androidx.lifecycle.viewModelScope
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.AutoOpenDelay
import dev.jamesrich.sofaking.data.BufferPreference
import dev.jamesrich.sofaking.data.ClockFormat
import dev.jamesrich.sofaking.data.DecoderPreference
import dev.jamesrich.sofaking.data.GuideDensity
import dev.jamesrich.sofaking.data.StartupTarget
import dev.jamesrich.sofaking.data.TopLevelTab
import dev.jamesrich.sofaking.data.CachedLineup
import dev.jamesrich.sofaking.data.CaptionStyle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.FakeHttp
import dev.jamesrich.sofaking.data.GuideWindow
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.LineupCache
import dev.jamesrich.sofaking.data.MemoryDataStore
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.ServerCapabilities
import dev.jamesrich.sofaking.data.SettingsStore
import dev.jamesrich.sofaking.data.TrackChoice
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val NOW = "2026-10-10T10:17:42Z"

@OptIn(ExperimentalCoroutinesApi::class)
class LineupViewModelTest {
    private val now = Instant.parse("2026-10-10T10:17:42Z")

    /** Settings in memory; only the server and the last channel matter here. */
    private class FakeSettings(initial: AppSettings) : SettingsStore {
        override val settings = MutableStateFlow(initial)
        override suspend fun setServer(serverUrl: String, apiToken: String) = settings.update { it.copy(serverUrl = serverUrl, apiToken = apiToken) }
        override suspend fun toggleFavorite(guideId: String) = settings.update { it.copy(favorites = it.favorites xor guideId) }
        override suspend fun setLastChannel(guideId: String) =
            settings.update { it.copy(lastChannel = guideId, recentChannels = AppSettings.recentWith(it.recentChannels, guideId)) }
        override suspend fun setGuideFilter(key: String?) = settings.update { it.copy(guideFilter = key) }
        override suspend fun setCaptionsEnabled(enabled: Boolean) = settings.update { it.copy(captionsEnabled = enabled) }
        override suspend fun setCaptionAdvanceMs(ms: Int) = settings.update { it.copy(captionAdvanceMs = ms) }
        override suspend fun setChannelCaptionAdvance(guideId: String, ms: Int) = settings.update { it.copy(captionAdvanceByChannel = it.captionAdvanceByChannel + (guideId to ms)) }
        override suspend fun setSmoothCaptions(enabled: Boolean) = settings.update { it.copy(smoothCaptions = enabled) }
        override suspend fun setCaptionStyle(style: CaptionStyle) = settings.update { it.copy(captionStyle = style) }
        override suspend fun setAutoOpenDelay(delay: AutoOpenDelay) = settings.update { it.copy(autoOpenDelay = delay) }
        override suspend fun setPreviewSound(enabled: Boolean) = settings.update { it.copy(previewSound = enabled) }
        override suspend fun setKeyLegendShown(count: Int) = settings.update { it.copy(keyLegendShown = count) }
        override suspend fun setStartup(target: StartupTarget, guideId: String?) =
            settings.update { it.copy(startup = target, startupChannel = guideId.takeIf { target == StartupTarget.CHANNEL }) }
        override suspend fun setLastTab(tab: TopLevelTab) = settings.update { it.copy(lastTab = tab) }
        override suspend fun setClockFormat(format: ClockFormat) = settings.update { it.copy(clockFormat = format) }
        override suspend fun setGuideDensity(density: GuideDensity) = settings.update { it.copy(guideDensity = density) }
        override suspend fun setChannelHidden(guideId: String, hidden: Boolean) =
            settings.update { it.copy(hiddenChannels = if (hidden) it.hiddenChannels + guideId else it.hiddenChannels - guideId) }
        override suspend fun setChannelsHidden(guideIds: Collection<String>, hidden: Boolean) =
            settings.update { it.copy(hiddenChannels = if (hidden) it.hiddenChannels + guideIds else it.hiddenChannels - guideIds.toSet()) }
        override suspend fun setZapSkipsHidden(enabled: Boolean) = settings.update { it.copy(zapSkipsHidden = enabled) }
        override suspend fun setSkipCommercials(enabled: Boolean) = settings.update { it.copy(skipCommercials = enabled) }
        override suspend fun setTrackChoice(showId: String, choice: TrackChoice) = settings.update { it.copy(trackChoices = it.trackChoices + (showId to choice)) }
        override suspend fun setFavoritesOrder(order: List<String>) = settings.update { it.copy(favoritesOrder = order) }
        override suspend fun setMatchFrameRate(enabled: Boolean) = settings.update { it.copy(playback = it.playback.copy(matchFrameRate = enabled)) }
        override suspend fun setDecoder(decoder: DecoderPreference) = settings.update { it.copy(playback = it.playback.copy(decoder = decoder)) }
        override suspend fun setChannelDecoder(guideId: String, decoder: DecoderPreference?) = settings.update {
            val by = if (decoder == null) it.playback.decoderByChannel - guideId else it.playback.decoderByChannel + (guideId to decoder)
            it.copy(playback = it.playback.copy(decoderByChannel = by))
        }
        override suspend fun setBuffer(buffer: BufferPreference) = settings.update { it.copy(playback = it.playback.copy(buffer = buffer)) }
        override suspend fun ensureClientId(): String {
            if (settings.value.clientId.isEmpty()) settings.update { it.copy(clientId = "test-client") }
            return settings.value.clientId
        }
        override suspend fun applyServerPreferences(hidden: Set<String>, favorites: List<String>, recent: List<String>, syncedAt: String) =
            settings.update { it.copy(hiddenChannels = hidden, favorites = favorites.toSet(), favoritesOrder = favorites, recentChannels = recent, preferencesSyncedAt = syncedAt) }
        override suspend fun setPreferencesSyncedAt(syncedAt: String) = settings.update { it.copy(preferencesSyncedAt = syncedAt) }
        override suspend fun setSettingsPin(hash: String?) = settings.update { it.copy(settingsPin = hash.orEmpty()) }

        private infix fun Set<String>.xor(id: String) = if (id in this) this - id else this + id
    }

    /** A server with a catalog of [channelIds], a one-programme guide per channel, and switches to fail either. */
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
                    // One programme per channel at the start of the window asked for, so a second day reads apart from the first.
                    val from = Regex(""""from":"([^"]+)"""").find(request.body)?.groupValues?.get(1)
                    val start = from?.let { Instant.parse(it) } ?: Instant.parse("2026-10-10T10:00:00Z")
                    val rows = channelIds.joinToString(",") { id ->
                        if (id in removed) """{"channelId":"$id","status":"removed","programmes":[]}"""
                        else """{"channelId":"$id","status":"available","programmes":[{"id":"p-$id","channelId":"$id","startsAt":"$start","endsAt":"${start + 1.hours}","title":"Show $id"}]}"""
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
    private val settings = FakeSettings(AppSettings(serverUrl = "http://sofa.test", apiToken = "t"))
    private var viewModel: LineupViewModel? = null

    private fun TestScope.start(server: FakeServer): LineupViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return LineupViewModel(
            store = settings,
            lineupCache = cacheStore,
            sessions = PlaybackSessions(scope = backgroundScope),
            newApi = { url, token -> server.http.api(token, "$url/") },
            viewerSync = null,
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
    fun `a day ahead is loaded once and merged into the guide until the live window passes it`() = viewModelTest {
        val server = FakeServer(this)
        val viewModel = start(server)
        viewModel.state.first { it is LineupState.Ready && !it.lineup.guideLoading }
        val live = GuideWindow.start(Clock.System.now())
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

    @Test
    fun `pairing saves the server and the token it hands out while a refused code saves nothing`() = viewModelTest {
        val server = FakeServer(this)
        settings.settings.value = AppSettings()
        val viewModel = start(server)
        assertEquals(LineupState.Unconfigured, viewModel.state.first { it != LineupState.Loading })

        assertEquals(PairingFailure.INVALID_CODE, viewModel.pair("http://sofa.test", "111 111"))
        assertEquals("", settings.settings.value.serverUrl)
        assertEquals(PairingFailure.INVALID_CODE, viewModel.pair("http://sofa.test", "12"), "a malformed code is not even sent")
        assertEquals(1, server.http.requests.count { it.path == "/api/v1/pair" })

        assertNull(viewModel.pair("http://sofa.test/", "654 321"))
        assertEquals("http://sofa.test", settings.settings.value.serverUrl)
        assertEquals("issued-token", settings.settings.value.apiToken)
        assertTrue(server.http.requests.last { it.path == "/api/v1/pair" }.body.contains(""""clientId":"test-client""""))
        assertIs<LineupState.Ready>(viewModel.state.first { it is LineupState.Ready })
        assertEquals("Bearer issued-token", server.http.requests.last { it.path == "/api/v1/channels" }.headers["Authorization"])
    }
}
