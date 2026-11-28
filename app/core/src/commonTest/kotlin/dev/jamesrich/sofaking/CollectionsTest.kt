package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.LibraryRepository
import dev.jamesrich.sofaking.data.SofaKingApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A library's collections: the grid's filter by one, and the collection a movie's page lists. */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsTest {
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

    private fun movie(id: String) =
        """{"id":"$id","libraryId":"lib","kind":"movie","title":"Movie $id","addedAt":"2026-10-10T13:00:00Z"}"""

    @Test
    fun `the grid names the library's collections and lists one's movies when asked`() = fixture.viewModelTest {
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/home" -> 200 to """{"shelves":[]}"""

                "/api/v1/libraries/lib/items" ->
                    200 to """{"items":[${movie("m1")}],"total":1,"collections":["Alien Collection"]}"""

                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()

        model.openGrid("lib")
        runCurrent()
        assertEquals(listOf("Alien Collection"), model.grid.value!!.collections)
        assertNull(model.grid.value!!.collection)
        assertTrue(server.http.requests.none { "collection=" in it.query }, server.http.requests.last().query)

        model.openGrid("lib", collection = "Alien Collection")
        assertEquals(listOf("Alien Collection"), model.grid.value!!.collections, "the menu stays while the page loads")
        assertEquals("Alien Collection", model.grid.value!!.collection)
        runCurrent()
        assertTrue("collection=Alien" in server.http.requests.last().query, server.http.requests.last().query)

        // The same choice again asks for nothing; another library starts without the names.
        val asked = server.requests.size
        model.openGrid("lib", collection = "Alien Collection")
        runCurrent()
        assertEquals(asked, server.requests.size)
        model.openGrid("other")
        assertEquals(emptyList(), model.grid.value!!.collections)
    }

    @Test
    fun `a movie's page carries its collection in the server's order`() = runTest {
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/items/m2" -> 200 to """{"item":${movie("m2")},"collection":
                    {"name":"Alien Collection","items":[${movie("m1")},${movie("m2")},${movie("m3")}]}}"""

                "/api/v1/library/items/m9" -> 200 to """{"item":${movie("m9")}}"""

                else -> null
            }
        }
        val repository = LibraryRepository(server.http.api())
        val collection = repository.detail("m2").collection
        assertEquals("Alien Collection", collection?.name)
        assertEquals(listOf("m1", "m2", "m3"), collection?.items?.map { it.id })
        assertNull(repository.detail("m9").collection)
    }
}
