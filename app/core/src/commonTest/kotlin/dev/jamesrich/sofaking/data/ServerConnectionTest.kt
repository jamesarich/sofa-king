package dev.jamesrich.sofaking.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectionTest {
    /** The servers at `http://<name>.test`; [bootstrap] answers each one's capabilities, null for a 503. */
    private class FakeServers(private val scope: TestScope) {
        var bootstrap: (server: String) -> String? = { null }

        /** When each bootstrap was asked for, in virtual milliseconds. */
        val bootstrapTimes = mutableListOf<Long>()

        /** Every request, as `METHOD server/path`. */
        val requests = mutableListOf<String>()

        private val servers = HashMap<String, FakeHttp>()

        /** Every API client the connection built, oldest first. */
        val built = mutableListOf<SofaKingApi>()

        fun api(url: String, token: String): SofaKingApi {
            val name = url.substringAfter("://").substringBefore(".")
            return servers.getOrPut(name) { http(name) }.api(token, "$url/").also { built += it }
        }

        private fun http(server: String) = FakeHttp(scope) { request ->
            requests += "${request.method} $server${request.path}"
            when (request.path) {
                "/api/v1/bootstrap" -> {
                    bootstrapTimes += scope.currentTime
                    bootstrap(server)?.let {
                        json("""{"serverId":"$server","serverTime":"2026-10-10T10:00:00Z","capabilities":$it}""")
                    }
                        ?: apiError(503, "NOT_READY")
                }

                "/api/v1/playback-sessions" -> json(
                    """{"id":"$server-s1","channelId":"c1","intent":"watch","expiresAt":"2026-10-10T10:00:00Z",""" +
                        """"descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/1/root.m3u8","epoch":1}}""",
                    status = 201,
                )

                "/api/v1/playback-sessions/$server-s1" -> json("", status = 204)

                else -> apiError(404, "RESOURCE_NOT_FOUND")
            }
        }
    }

    private val settings = MutableStateFlow<AppSettings?>(AppSettings(serverUrl = "http://a.test", apiToken = "t"))

    private fun TestScope.connection(
        servers: FakeServers,
        scope: CoroutineScope = backgroundScope,
        viewerSync: ViewerSync? = null,
    ) = ServerConnection(
        settings = settings,
        scope = scope,
        sessions = PlaybackSessions(backgroundScope),
        newApi = servers::api,
        viewerSync = viewerSync,
    )

    @Test
    fun `the bootstrap is asked again with a backoff that doubles up to five minutes`() = runTest {
        val servers = FakeServers(this)
        val connection = connection(servers)
        runCurrent()
        assertEquals(ServerCapabilities(), connection.capabilities.value)

        advanceTimeBy(11.minutes)
        val gaps = servers.bootstrapTimes.zipWithNext { a, b -> (b - a) / 1000 }
        assertEquals(listOf(5L, 10, 20, 40, 80, 160, 300), gaps)

        servers.bootstrap = { """{"catalog":true,"recordings":true,"library":false}""" }
        advanceTimeBy(5.minutes)
        assertEquals(ServerCapabilities(recordings = true), connection.capabilities.value)
        val asked = servers.bootstrapTimes.size
        advanceTimeBy(30.minutes)
        assertEquals(asked, servers.bootstrapTimes.size, "an answer stops the asking")
    }

    @Test
    fun `a server change releases the old server's session and starts its capabilities over`() = runTest {
        val servers = FakeServers(this).apply {
            bootstrap =
                { server -> """{"recordings":true,"library":true}""".takeIf { server == "a" } }
        }
        val connection = connection(servers)
        runCurrent()
        assertEquals(ServerCapabilities(recordings = true, library = true), connection.capabilities.value)
        connection.sessions.tune("c1", PlaybackIntent.WATCH)

        settings.value = AppSettings(serverUrl = "http://b.test", apiToken = "t")
        runCurrent()

        assertTrue("DELETE a/api/v1/playback-sessions/a-s1" in servers.requests, servers.requests.toString())
        assertNull(connection.sessions.current.value)
        assertEquals(ServerCapabilities(), connection.capabilities.value, "the new server has not answered")
        assertEquals("b-s1", connection.sessions.tune("c1", PlaybackIntent.WATCH).sessionId)
        assertEquals(listOf(true, false), servers.built.map { it.closed }, "the old server's client is closed")
    }

    @Test
    fun `the server's client closes when the connection's scope ends`() = runTest {
        val servers = FakeServers(this)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        connection(servers, scope)
        runCurrent()
        assertEquals(listOf(false), servers.built.map { it.closed })

        scope.cancel()
        runCurrent()

        assertEquals(listOf(true), servers.built.map { it.closed })
    }

    @Test
    fun `a settings write the store refuses during the sync is logged and the connection carries on`() = runTest {
        val refusing = object : DataStore<Preferences> {
            override val data = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        val servers = FakeServers(this).apply { bootstrap = { """{"library":true}""" } }
        // Not the background scope, which reports every failure of its children, handled or not.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val connection =
                connection(
                    servers,
                    scope,
                    ViewerSync(DataStoreSettingsStore(refusing), ClientInfo("android-tv", "Den")),
                )
            runCurrent()
            assertTrue(scope.isActive)
            assertEquals(ServerCapabilities(library = true), connection.capabilities.value)
        } finally {
            scope.cancel()
        }
    }
}
