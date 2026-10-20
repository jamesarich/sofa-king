package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.FAKE_BASE
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.PlayProgress
import dev.jamesrich.sofaking.data.PlaybackProfile
import dev.jamesrich.sofaking.data.SofaKingApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val fixture = ViewModelFixture()

    @AfterTest
    fun tearDown() = fixture.tearDown()

    private fun item(id: String, kind: String = "movie", extra: String = "") =
        """{"id":"$id","libraryId":"lib","kind":"$kind","title":"Title $id","addedAt":"2026-10-10T13:00:00Z","poster":"/artwork/$id/poster?v=1"$extra}"""

    /** The model on [server], stopped as the test ends. */
    private suspend fun TestScope.viewModel(server: RouteServer, api: SofaKingApi? = server.http.api()): LibraryViewModel =
        fixture.started(LibraryViewModel(MutableStateFlow(api), fixture.sessions(this, api), timeSource = testScheduler.timeSource, saveInterval = 10.seconds))

    @Test
    fun `the home maps shelves and libraries and resolves artwork against the server`() = fixture.viewModelTest {
        val server = RouteServer(this) { method, path, _ ->
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
        assertTrue(episode.progress.inProgress)
        assertEquals(listOf(LibraryKind.MOVIES, LibraryKind.OTHER), home.libraries.map { it.kind })
    }

    @Test
    fun `a server without libraries reads as unavailable and none configured as unconfigured`() = fixture.viewModelTest {
        val model = viewModel(RouteServer(this) { _, _, _ -> null })
        runCurrent()
        assertEquals(LibraryHomeState.Unavailable, model.home.value)

        val none = viewModel(RouteServer(this) { _, _, _ -> null }, api = null)
        runCurrent()
        assertEquals(LibraryHomeState.Unconfigured, none.home.value)
    }

    @Test
    fun `every Recently added row has its own key`() = fixture.viewModelTest {
        val server = RouteServer(this) { _, path, _ ->
            if (path == "/api/v1/library/home") 200 to """{"shelves":[
                {"id":"continue","title":"Continue watching","items":[${item("e1", "episode")}]},
                {"id":"recent","title":"Recently added in Movies","libraryId":"movies","items":[${item("m1")}]},
                {"id":"recent","title":"Recently added in TV shows","libraryId":"shows","items":[${item("s1", "show")}]}],
                "libraries":[{"id":"movies","name":"Movies","kind":"movies"},{"id":"shows","name":"TV shows","kind":"shows"}]}""" else null
        }
        val model = viewModel(server)
        runCurrent()

        val shelves = assertIs<LibraryHomeState.Ready>(model.home.value).home.shelves
        assertEquals(listOf("continue", "recent:movies", "recent:shows"), shelves.map { it.key })
    }

    @Test
    fun `a home with no library is loaded again until the server adds one`() = fixture.viewModelTest {
        var libraries = ""
        val server = RouteServer(this) { _, path, _ ->
            if (path == "/api/v1/library/home") 200 to """{"shelves":[],"libraries":[$libraries]}""" else null
        }
        val model = viewModel(server)
        runCurrent()
        assertTrue(assertIs<LibraryHomeState.Ready>(model.home.value).home.libraries.isEmpty())

        libraries = """{"id":"lib","name":"Movies","kind":"movies","items":3}"""
        advanceTimeBy(5.minutes + 1.seconds)
        assertEquals(listOf("lib"), assertIs<LibraryHomeState.Ready>(model.home.value).home.libraries.map { it.id })

        advanceTimeBy(30.minutes)
        assertEquals(2, server.requests.count { it.contains("/library/home") }, "a home with a library is not polled")
    }

    @Test
    fun `the grid pages with the cursor and keeps itself when opened again the same way`() = fixture.viewModelTest {
        val server = RouteServer(this) { _, path, _ ->
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
    }

    @Test
    fun `a genre reloads the grid and keeps the library's genres while it loads`() = fixture.viewModelTest {
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                "/api/v1/libraries/lib/items" -> 200 to """{"items":[${item("m1")}],"total":1,"genres":["Comedy","Drama"]}"""
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        model.openGrid("lib")
        runCurrent()
        assertEquals(listOf("Comedy", "Drama"), model.grid.value!!.genres)

        model.openGrid("lib", genre = "Drama")
        assertEquals(listOf("Comedy", "Drama"), model.grid.value!!.genres, "the chips stay while the page loads")
        assertEquals("Drama", model.grid.value!!.genre)
        runCurrent()
        assertEquals(2, server.requests.count { it.contains("/items") })

        model.openGrid("other")
        assertEquals(emptyList(), model.grid.value!!.genres, "another library starts without them")
    }

    @Test
    fun `a playing item saves its position every ten seconds and at once when it stops`() = fixture.viewModelTest {
        val saves = mutableListOf<String>()
        val server = RouteServer(this) { method, path, body ->
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
    }

    @Test
    fun `play resumes an item in progress unless asked to start over`() = fixture.viewModelTest {
        val opens = mutableListOf<String>()
        val server = RouteServer(this) { method, path, body ->
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
    }

    @Test
    fun `up next is the next episode in the season then the first of the next season`() = fixture.viewModelTest {
        fun episode(id: String, season: String) = item(id, "episode", ""","parentId":"$season","showId":"show"""")
        val server = RouteServer(this) { _, path, _ ->
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
    }

    @Test
    fun `marking an episode watched reloads its pages with its show's grid card and the home`() = fixture.viewModelTest {
        var watched = false
        fun episode() = item("e1", "episode", ""","parentId":"s1","showId":"show","playState":{"positionMs":0,"watched":$watched}""")
        fun show() = item("show", "show", ""","children":1,"unwatched":${if (watched) 0 else 1}""")
        val server = RouteServer(this) { method, path, body ->
            when {
                path == "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                path == "/api/v1/libraries/lib/items" -> 200 to """{"items":[${show()},${item("m1")}],"total":2}"""
                method == "PUT" && path == "/api/v1/library/items/e1/play-state" -> {
                    watched = "\"watched\":true" in body
                    200 to episode()
                }
                path == "/api/v1/library/items/e1" -> 200 to """{"item":${episode()}}"""
                path == "/api/v1/library/items/s1" -> 200 to """{"item":${item("s1", "season", ""","parentId":"show","showId":"show"""")},"children":[${episode()}]}"""
                path == "/api/v1/library/items/show" -> 200 to """{"item":${show()}}"""
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()
        model.openGrid("lib")
        val season = model.detail("s1")
        model.detail("e1")
        model.detail("show")
        runCurrent()
        fun gets(id: String) = server.requests.count { it == "GET /api/v1/library/items/$id" }
        assertEquals(listOf(1, 1, 1), listOf(gets("e1"), gets("s1"), gets("show")))

        model.setWatched(MediaItem("e1", "lib", ItemKind.EPISODE, "Title e1", parentId = "s1", showId = "show"), watched = true)
        runCurrent()

        assertEquals("""PUT /api/v1/library/items/e1/play-state {"watched":true}""", server.requests.first { it.startsWith("PUT") })
        assertEquals(listOf(2, 2), listOf(gets("e1"), gets("s1")), "the episode's page and its season's reload")
        assertEquals(3, gets("show"), "the show's page reloads, and its grid card with it")
        assertEquals(0, model.grid.value!!.items.first { it.id == "show" }.unwatched)
        assertTrue(assertIs<ItemDetailState.Ready>(season.value).detail.children.single().progress!!.watched)
        assertEquals(2, server.requests.count { it == "GET /api/v1/library/home" }, "the home reloads")
    }

    @Test
    fun `search trims the query and finds nothing for a blank one or when the server fails`() = fixture.viewModelTest {
        var fail = false
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                "/api/v1/library/search" -> if (fail) 503 to """{"code":"NOT_READY","message":"NOT_READY","retryable":true}""" else 200 to """{"items":[${item("m1")}],"total":1}"""
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        assertEquals(emptyList(), model.search("   "))
        assertTrue(server.requests.none { "/library/search" in it }, "a blank query is not sent")
        assertEquals(listOf("m1"), model.search("  dune ").map { it.id })
        assertEquals("q=dune", server.http.requests.last().query)
        fail = true
        assertEquals(emptyList(), model.search("dune"))
    }

    @Test
    fun `a newer load of an item page wins over an older one still on its way`() = fixture.viewModelTest {
        var loads = 0
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                "/api/v1/library/items/m1" -> {
                    val title = if (++loads == 1) "Old" else "New"
                    // The first answer is slow, so it would land after the second.
                    if (loads == 1) delay(5.seconds)
                    200 to """{"item":{"id":"m1","libraryId":"lib","kind":"movie","title":"$title"}}"""
                }
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        val page = model.detail("m1")
        runCurrent()
        model.detail("m1", refresh = true)
        advanceTimeBy(10.seconds)
        assertEquals("New", assertIs<ItemDetailState.Ready>(page.value).detail.item.title)
    }
}
