package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.SofaKingApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MusicRadioTest {
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

    private fun song(id: String) =
        """{"id":"$id","libraryId":"music","kind":"track","title":"Song $id","addedAt":"2026-10-10T13:00:00Z"}"""

    private val artist = MediaItem(id = "r1", libraryId = "music", kind = ItemKind.ARTIST, title = "Band")

    @Test
    fun `a radio is the songs the server names in its order`() = fixture.viewModelTest {
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/items/r1/radio" -> 200 to """{"items":[${song("s2")},${song("s1")}],"total":2}"""
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()
        val songs = model.radio(artist)
        assertEquals(listOf("s2", "s1"), songs.map { it.id })
        assertTrue(songs.all { it.kind == ItemKind.TRACK })
        assertTrue("limit=50" in server.http.requests.last().query, server.http.requests.last().query)
    }

    @Test
    fun `a server without radio gives none`() = fixture.viewModelTest {
        val server = RouteServer(this) { _, _, _ -> null }
        val model = viewModel(server)
        runCurrent()
        assertEquals(emptyList(), model.radio(artist))
    }
}
