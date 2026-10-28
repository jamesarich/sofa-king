package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.MusicView
import dev.jamesrich.sofaking.data.SofaKingApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MusicGridTest {
    private val fixture = ViewModelFixture()

    @AfterTest
    fun tearDown() = fixture.tearDown()

    private suspend fun TestScope.viewModel(server: RouteServer, api: SofaKingApi? = server.http.api()) =
        fixture.started(
            LibraryViewModel(
                MutableStateFlow(api),
                fixture.sessions(this, api),
                timeSource = testScheduler.timeSource,
                saveInterval = 10.seconds,
                kidsMode = flowOf(false),
            ),
        )

    private fun entry(id: String, kind: String) =
        """{"id":"$id","libraryId":"music","kind":"$kind","title":"Title $id","addedAt":"2026-10-10T13:00:00Z"}"""

    private fun musicServer(scope: TestScope) = RouteServer(scope) { _, path, _ ->
        when (path) {
            "/api/v1/libraries/music/items" -> 200 to """{"items":[${entry("r1", "artist")}],"total":1}"""
            else -> null
        }
    }

    @Test
    fun `a music grid lists artists until the albums view is asked for`() = fixture.viewModelTest {
        val server = musicServer(this)
        val model = viewModel(server)
        runCurrent()

        model.openGrid("music")
        runCurrent()
        assertEquals(MusicView.ARTISTS, model.grid.value!!.view)
        assertFalse("kind=" in server.http.requests.last().query, server.http.requests.last().query)

        model.openGrid("music", view = MusicView.ALBUMS)
        runCurrent()
        assertEquals(MusicView.ALBUMS, model.grid.value!!.view)
        assertTrue("kind=album" in server.http.requests.last().query, server.http.requests.last().query)
        assertEquals(ItemKind.ARTIST, model.grid.value!!.items.single().kind, "the fake answers with the same items")
    }

    @Test
    fun `the albums view starts without the artists' genre and genres`() = fixture.viewModelTest {
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/libraries/music/items" ->
                    200 to """{"items":[${entry("a1", "album")}],"total":1,"genres":["Rock"]}"""

                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        model.openGrid("music", LibrarySort.ADDED, genre = "Rock")
        runCurrent()
        assertEquals(listOf("Rock"), model.grid.value!!.genres)

        model.openGrid("music", view = MusicView.ALBUMS)
        assertEquals(emptyList(), model.grid.value!!.genres, "the chips of the artists do not stay for the albums")
        assertEquals(null, model.grid.value!!.genre)
        runCurrent()
        assertEquals(LibrarySort.TITLE, model.grid.value!!.sort)
    }
}
