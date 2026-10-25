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
class HouseholdSyncTest {
    private val store = DataStoreSettingsStore(MemoryDataStore(emptyPreferences()))

    /** A server holding the household's one shared document, empty until a device writes it. */
    private class FakeServer(scope: TestScope) {
        var stored = """{"favorites":[],"hidden":[],"updatedAt":""}"""
        var stamp = 1
        val http = FakeHttp(scope) { request ->
            when {
                request.path == PATH && request.method == "GET" -> json(stored)

                request.path == PATH && request.method == "PUT" -> {
                    stored = request.body.dropLast(1) + ""","updatedAt":"2026-10-25T00:0${stamp++}:00Z"}"""
                    json(stored)
                }

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private fun TestScope.start(server: FakeServer, offered: Boolean = true) {
        val sync = HouseholdSync(store, settleTime = 3.seconds, retryAfter = 10.seconds)
        backgroundScope.launch { sync.run(MutableStateFlow(server.http.api()), MutableStateFlow(offered)) }
        runCurrent()
    }

    private fun TestScope.settle() {
        advanceTimeBy(4.seconds)
        runCurrent()
    }

    @Test
    fun `nothing is read or sent until the device shares and the server offers it`() = runTest {
        val server = FakeServer(this)
        store.toggleFavorite("f1")
        start(server, offered = false)
        store.setShareFavorites(true)
        settle()
        assertTrue(server.http.requests.isEmpty())
    }

    @Test
    fun `the first device to share pushes its favorites in order and its hidden channels`() = runTest {
        val server = FakeServer(this)
        store.toggleFavorite("f2")
        store.toggleFavorite("f1")
        store.setFavoritesOrder(listOf("f1", "f2"))
        store.setChannelHidden("h1", true)
        store.setShareFavorites(true)
        start(server)
        settle()
        val put = server.http.requests.last()
        assertEquals("PUT", put.method)
        assertEquals("""{"favorites":["f1","f2"],"hidden":["h1"]}""", put.body)
        assertEquals("2026-10-25T00:01:00Z", store.settings.first().householdSyncedAt)
    }

    @Test
    fun `a shared copy this device has not seen is taken over and not pushed back`() = runTest {
        val server = FakeServer(this)
        server.stored = """{"favorites":["b","a"],"hidden":["x"],"updatedAt":"2026-10-24T09:00:00Z"}"""
        store.toggleFavorite("mine")
        store.setLastChannel("mine")
        store.setShareFavorites(true)
        start(server)
        settle()
        val settings = store.settings.first()
        assertEquals(setOf("a", "b"), settings.favorites)
        assertEquals(listOf("b", "a"), settings.favoritesOrder)
        assertEquals(setOf("x"), settings.hiddenChannels)
        assertEquals(listOf("mine"), settings.recentChannels, "the recent list stays the device's own")
        assertEquals("2026-10-24T09:00:00Z", settings.householdSyncedAt)
        assertEquals(listOf("GET"), server.http.requests.map { it.method })
    }

    private companion object {
        const val PATH = "/api/v1/household/preferences"
    }
}
