package dev.jamesrich.sofaking.data

import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerSyncTest {
    private val store = DataStoreSettingsStore(MemoryDataStore(emptyPreferences()))
    private val client = ClientInfo("android-tv", "Den")

    /** A server that may hold one preferences document for every client and records what it is sent. */
    private class FakeServer(scope: TestScope) {
        var stored: String? = null
        var putStatus = 200
        var getStatus = 200
        var stamp = 1
        val http = FakeHttp(scope) { request ->
            when {
                request.path.startsWith("/api/v1/preferences/") && request.method == "GET" ->
                    if (getStatus != 200) {
                        apiError(getStatus, "NOT_READY")
                    } else {
                        stored?.let { json(it) }
                            ?: apiError(404, "VIEWER_NOT_FOUND")
                    }

                request.path.startsWith("/api/v1/preferences/") && request.method == "PUT" -> {
                    if (putStatus != 200) return@FakeHttp apiError(putStatus, "RESOURCE_NOT_FOUND")
                    stored = request.body.dropLast(1) + ""","updatedAt":"2026-10-16T00:0${stamp++}:00Z"}"""
                    json(stored!!)
                }

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private fun TestScope.start(server: FakeServer, settle: kotlin.time.Duration = 3.seconds) {
        val sync = ViewerSync(store, client, settleTime = settle, retryAfter = 10.seconds)
        backgroundScope.launch { sync.run(MutableStateFlow(server.http.api())) }
        runCurrent()
    }

    @Test
    fun `a local change is pushed after it settles with the device's label and the favorites in order`() = runTest {
        val server = FakeServer(this)
        store.setChannelHidden("h1", true)
        start(server)
        assertEquals(listOf("GET"), server.http.requests.map { it.method }, "the server's copy is read first")

        store.toggleFavorite("f2")
        store.toggleFavorite("f1")
        store.setFavoritesOrder(listOf("f1", "f2"))
        store.setLastChannel("f1")
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, server.http.requests.size, "nothing is sent until the changes settle")
        advanceTimeBy(2.seconds)
        runCurrent()

        val put = server.http.requests.last()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/preferences/${store.settings.first().clientId}", put.path)
        assertEquals("Bearer secret", put.headers["Authorization"])
        assertEquals(
            """{"hidden":["h1"],"favorites":["f1","f2"],"recent":["f1"],"name":"Den","platform":"android-tv"}""",
            put.body,
        )
        assertEquals("2026-10-16T00:01:00Z", store.settings.first().preferencesSyncedAt)
        assertEquals(2, server.http.requests.size, "the push is not repeated for the stamp it set")
    }

    @Test
    fun `a server copy this device has not seen is taken over and not pushed back`() = runTest {
        val server = FakeServer(this)
        server.stored =
            """{"hidden":["x"],"favorites":["b","a"],"recent":["a","b"],"name":"Old TV","platform":"tvos","updatedAt":"2026-10-15T20:00:00Z"}"""
        store.toggleFavorite("local")
        start(server)

        val settings = store.settings.first()
        assertEquals(setOf("x"), settings.hiddenChannels)
        assertEquals(setOf("a", "b"), settings.favorites)
        assertEquals(listOf("b", "a"), settings.favoritesOrder)
        assertEquals(listOf("a", "b"), settings.recentChannels)
        assertEquals(
            "a",
            settings.lastChannel,
            "the newest recent channel becomes the last channel when there was none",
        )
        assertEquals("2026-10-15T20:00:00Z", settings.preferencesSyncedAt)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(
            listOf("GET"),
            server.http.requests.map {
                it.method
            },
            "taking the server's copy is not a change to push",
        )

        // A change after that is pushed as usual.
        store.setChannelHidden("y", true)
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(listOf("GET", "PUT"), server.http.requests.map { it.method })
        assertTrue(server.stored!!.contains(""""hidden":["x","y"]"""))
    }

    @Test
    fun `a server copy this device wrote last is left alone`() = runTest {
        val server = FakeServer(this)
        server.stored = """{"hidden":["x"],"favorites":[],"recent":[],"updatedAt":"2026-10-15T20:00:00Z"}"""
        store.applyServerPreferences(setOf("x", "mine"), emptyList(), emptyList(), "2026-10-15T20:00:00Z")
        start(server)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(
            setOf("x", "mine"),
            store.settings.first().hiddenChannels,
            "the same stamp means this device's copy is the newer one",
        )
        advanceTimeBy(4.seconds)
        runCurrent()
        // The local copy differs from the server's, so it is pushed once the first collection settles.
        assertEquals(listOf("GET", "PUT"), server.http.requests.map { it.method })
    }

    @Test
    fun `a failed read of the server's copy is retried and never overwritten by this device`() = runTest {
        val server = FakeServer(this)
        server.stored = """{"hidden":["x"],"favorites":["a"],"recent":["a"],"updatedAt":"2026-10-15T20:00:00Z"}"""
        server.getStatus = 503
        start(server)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(
            listOf("GET"),
            server.http.requests.map {
                it.method
            },
            "nothing is pushed before the server answers",
        )

        server.getStatus = 200
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(listOf("GET", "GET"), server.http.requests.map { it.method })
        assertEquals(
            setOf("x"),
            store.settings.first().hiddenChannels,
            "the server's copy is taken over once it answers",
        )
        assertTrue(server.stored!!.contains(""""hidden":["x"]"""))
    }

    @Test
    fun `a refused read is not retried and nothing is pushed`() = runTest {
        val server = FakeServer(this)
        server.getStatus = 403
        store.setChannelHidden("h1", true)
        start(server)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(listOf("GET"), server.http.requests.map { it.method })
    }

    @Test
    fun `a server without the routes is asked once and then left alone`() = runTest {
        val server = FakeServer(this)
        server.putStatus = 404
        store.setChannelHidden("h1", true)
        start(server)
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(listOf("GET", "PUT"), server.http.requests.map { it.method })
        store.setChannelHidden("h2", true)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(2, server.http.requests.size, "no retry against a server that does not keep preferences")
    }

    @Test
    fun `a failed push is retried`() = runTest {
        val server = FakeServer(this)
        server.putStatus = 503
        store.setChannelHidden("h1", true)
        start(server)
        advanceTimeBy(4.seconds)
        runCurrent()
        assertEquals(listOf("GET", "PUT"), server.http.requests.map { it.method })
        server.putStatus = 200
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(listOf("GET", "PUT", "PUT"), server.http.requests.map { it.method })
        assertEquals("2026-10-16T00:01:00Z", store.settings.first().preferencesSyncedAt)
    }
}
