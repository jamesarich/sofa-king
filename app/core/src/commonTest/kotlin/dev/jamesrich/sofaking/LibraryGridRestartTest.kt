package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.LibrarySort
import dev.jamesrich.sofaking.data.SofaKingApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryGridRestartTest {
    private val fixture = ViewModelFixture()

    @AfterTest
    fun tearDown() = fixture.tearDown()

    @Test
    fun `a grid opened before the server is known loads once it is`() = fixture.viewModelTest {
        val item = """{"id":"m1","libraryId":"lib","kind":"movie","title":"One","addedAt":"2026-10-10T13:00:00Z"}"""
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                "/api/v1/libraries/lib/items" -> 200 to """{"items":[$item],"total":1,"genres":["Drama"]}"""
                else -> null
            }
        }
        val apis = MutableStateFlow<SofaKingApi?>(null)
        val model = fixture.started(
            LibraryViewModel(
                apis,
                fixture.sessions(this, null),
                timeSource = testScheduler.timeSource,
                saveInterval = 10.seconds,
                kidsMode = flowOf(false),
            ),
        )
        runCurrent()
        // A page opened by its address asks for its grid first, in the order and with the filter it shows.
        model.openGrid("lib", LibrarySort.YEAR, genre = "Drama")
        runCurrent()
        assertEquals(0, server.requests.count { it.contains("/items") })

        apis.value = server.http.api()
        runCurrent()
        val grid = model.grid.value!!
        assertEquals(listOf("m1"), grid.items.map { it.id })
        assertEquals(LibrarySort.YEAR, grid.sort)
        assertEquals("Drama", grid.genre)
        assertEquals(false, grid.loading)

        // Signed out, no grid is open.
        apis.value = null
        runCurrent()
        assertEquals(null, model.grid.value)
    }
}
