package dev.jamesrich.sofaking

import androidx.lifecycle.viewModelScope
import dev.jamesrich.sofaking.data.FAKE_BASE
import dev.jamesrich.sofaking.data.FakeHttp
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.PlayProgress
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.data.SofaKingApi
import dev.jamesrich.sofaking.data.apiError
import dev.jamesrich.sofaking.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun item(id: String, kind: String = "movie", extra: String = "") =
        """{"id":"$id","libraryId":"lib","kind":"$kind","title":"Title $id","addedAt":"2026-10-10T13:00:00Z","poster":"/artwork/$id/poster?v=1"$extra}"""

    private class Server(scope: TestScope, val handler: suspend (String, String, String) -> Pair<Int, String>?) {
        val http = FakeHttp(scope) { request ->
            handler(request.method, request.path, request.body)?.let { (status, body) -> json(body, status) } ?: apiError(404, "RESOURCE_NOT_FOUND")
        }
        val requests get() = http.requests.map { it.toString() }
    }

    /** The model on [server]; its sessions are configured as LineupViewModel does for the app. */
    private suspend fun TestScope.viewModel(server: Server, api: SofaKingApi? = server.http.api()): LibraryViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sessions = PlaybackSessions(backgroundScope)
        sessions.configure(api)
        return LibraryViewModel(MutableStateFlow(api), sessions, timeSource = testScheduler.timeSource, saveInterval = 10.seconds)
    }

    @Test
    fun `the home maps shelves and libraries and resolves artwork against the server`() = runTest {
        val server = Server(this) { method, path, _ ->
            if (method == "GET" && path == "/api/v1/library/home") 200 to """{"shelves":[
                {"id":"continue","title":"Continue watching","items":[${item("e1", "episode", ""","showTitle":"Show","season":2,"episode":5,"playState":{"positionMs":600000,"durationMs":2400000,"watched":false}""")}]},
                {"id":"recent","title":"Recently added in Movies","libraryId":"lib","items":[]}],
                "libraries":[{"id":"lib","name":"Movies","kind":"movies","items":3},{"id":"new","name":"Odd","kind":"photos"}]}""" else null
        }
        val model = viewModel(server)
        runCurrent()

        val home = assertIs<LibraryHomeState.Ready>(model.home.value).home
        assertEquals(listOf("continue"), home.shelves.map { it.id }, "an empty row is dropped")
        val episode = home.shelves.single().items.single()
        assertEquals(ItemKind.EPISODE, episode.kind)
        assertEquals("S2 E5", episode.episodeLabel)
        assertEquals("${FAKE_BASE}artwork/e1/poster?v=1", episode.poster)
        assertEquals(Instant.parse("2026-10-10T13:00:00Z"), episode.addedAt)
        assertEquals(0.25f, episode.progress!!.fraction)
        assertTrue(episode.progress!!.inProgress)
        assertEquals(listOf(LibraryKind.MOVIES, LibraryKind.OTHER), home.libraries.map { it.kind })
        model.viewModelScope.cancel()
    }

    @Test
    fun `a server without libraries reads as unavailable and none configured as unconfigured`() = runTest {
        val model = viewModel(Server(this) { _, _, _ -> null })
        runCurrent()
        assertEquals(LibraryHomeState.Unavailable, model.home.value)
        model.viewModelScope.cancel()

        val none = viewModel(Server(this) { _, _, _ -> null }, api = null)
        runCurrent()
        assertEquals(LibraryHomeState.Unconfigured, none.home.value)
        none.viewModelScope.cancel()
    }

    @Test
    fun `the grid pages with the cursor and keeps itself when opened again the same way`() = runTest {
        val server = Server(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                "/api/v1/libraries/lib/items" -> 200 to """{"items":[${item("m1")},${item("m2")}],"total":3,"next":"c2"}"""
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        model.openGrid("lib", LibrarySort.ADDED)
        runCurrent()
        val first = model.grid.value!!
        assertEquals(listOf("m1", "m2"), first.items.map { it.id })
        assertEquals("c2", first.next)
        assertTrue(server.requests.last().contains("/api/v1/libraries/lib/items"))

        model.openGrid("lib", LibrarySort.ADDED)
        runCurrent()
        assertEquals(1, server.requests.count { it.contains("/items") }, "the same grid is not reloaded")

        model.loadMore()
        runCurrent()
        assertEquals(2, server.requests.count { it.contains("/items") })
        assertEquals(listOf("m1", "m2"), model.grid.value!!.items.map { it.id }, "a repeated item shows once")
        model.viewModelScope.cancel()
    }

    @Test
    fun `a playing item saves its position every ten seconds and at once when it stops`() = runTest {
        val saves = mutableListOf<String>()
        val server = Server(this) { method, path, body ->
            when {
                path == "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                method == "PUT" && path == "/api/v1/library/items/m1/play-state" -> {
                    saves += body
                    200 to item("m1")
                }
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        model.progress("m1", 1_000, 60_000)
        runCurrent()
        advanceTimeBy(5.seconds)
        model.progress("m1", 6_000, 60_000)
        runCurrent()
        assertEquals(emptyList(), saves, "nothing is written in the first ten seconds")
        advanceTimeBy(6.seconds)
        model.progress("m1", 12_000, 60_000)
        runCurrent()
        assertEquals(listOf("""{"positionMs":12000,"durationMs":60000}"""), saves)

        model.stopped("m1", 14_000, 60_000)
        runCurrent()
        assertEquals("""{"positionMs":14000,"durationMs":60000}""", saves.last())
        assertTrue(server.requests.count { it.startsWith("GET /api/v1/library/home") } >= 2, "the home reloads after a stop")
        model.viewModelScope.cancel()
    }

    @Test
    fun `play resumes an item in progress unless asked to start over`() = runTest {
        val opens = mutableListOf<String>()
        val server = Server(this) { method, path, body ->
            when {
                path == "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                method == "POST" && path == "/api/v1/vod-sessions" -> {
                    opens += body
                    201 to """{"id":"v1","itemId":"m1","mode":"direct","descriptor":{"protocol":"file","mimeType":"video/mp4","url":"/media/vod/v1/1/k/file.mp4","epoch":1}}"""
                }
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()
        val movie = MediaItem("m1", "lib", ItemKind.MOVIE, "Movie", progress = PlayProgress(300_000, 6_000_000, watched = false))

        assertEquals(300_000, model.play(movie, PlaybackProfile()).startAtMs)
        assertEquals(0, model.play(movie, PlaybackProfile(), fromStart = true).startAtMs)
        assertEquals(0, model.play(movie.copy(progress = movie.progress!!.copy(watched = true)), PlaybackProfile()).startAtMs, "a watched item starts over")
        assertEquals(3, opens.size)
        model.viewModelScope.cancel()
    }

    @Test
    fun `up next is the next episode in the season then the first of the next season`() = runTest {
        fun episode(id: String, season: String) = item(id, "episode", ""","parentId":"$season","showId":"show"""")
        val server = Server(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                "/api/v1/library/items/s1" -> 200 to """{"item":${item("s1", "season")},"children":[${episode("e1", "s1")},${episode("e2", "s1")}]}"""
                "/api/v1/library/items/s2" -> 200 to """{"item":${item("s2", "season")},"children":[${episode("e3", "s2")}]}"""
                "/api/v1/library/items/show" -> 200 to """{"item":${item("show", "show")},"children":[${item("s1", "season", ""","season":1""")},${item("s2", "season", ""","season":2""")}]}"""
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()
        fun mapped(id: String, season: String) = MediaItem(id, "lib", ItemKind.EPISODE, "Title $id", parentId = season, showId = "show")

        assertEquals("e2", model.upNext(mapped("e1", "s1"))?.id)
        assertEquals("e3", model.upNext(mapped("e2", "s1"))?.id, "the last of a season goes on to the next")
        assertEquals(null, model.upNext(mapped("e3", "s2")), "nothing after the show's last episode")
        assertEquals(null, model.upNext(MediaItem("m", "lib", ItemKind.MOVIE, "Movie")), "a movie has no next")
        model.viewModelScope.cancel()
    }
}
