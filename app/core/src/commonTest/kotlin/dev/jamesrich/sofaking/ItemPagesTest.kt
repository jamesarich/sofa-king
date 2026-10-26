package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** The item pages [LibraryViewModel] holds open, and the item screen's [ItemPageViewModel] that holds them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemPagesTest {
    private val fixture = ViewModelFixture()

    @AfterTest
    fun tearDown() = fixture.tearDown()

    private fun item(id: String, kind: String = "movie") =
        """{"id":"$id","libraryId":"lib","kind":"$kind","title":"Title $id","addedAt":"2026-10-10T13:00:00Z"}"""

    /** The model on [server], stopped as the test ends. */
    private suspend fun TestScope.viewModel(server: RouteServer): LibraryViewModel {
        val api = server.http.api()
        return fixture.started(
            LibraryViewModel(
                MutableStateFlow(api),
                fixture.sessions(this, api),
                timeSource = testScheduler.timeSource,
                saveInterval = 10.seconds,
            ),
        )
    }

    @Test
    fun `an item page open twice loads once and is forgotten once both let it go`() = fixture.viewModelTest {
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                "/api/v1/library/items/m1" -> 200 to """{"item":${item("m1")}}"""
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()
        fun gets() = server.requests.count { it == "GET /api/v1/library/items/m1" }

        val first = model.openItem("m1")
        val second = model.openItem("m1")
        runCurrent()
        assertEquals(1, gets(), "two holders share one load")
        first.close()
        first.close()
        model.openItem("m1").use { runCurrent() }
        assertEquals(1, gets(), "a page someone still holds is not loaded again")
        assertIs<ItemDetailState.Ready>(second.state.value)

        second.close()
        val again = model.openItem("m1")
        runCurrent()
        assertEquals(2, gets(), "a page nobody held loads afresh")
        assertIs<ItemDetailState.Ready>(again.state.value)
    }

    @Test
    fun `an item screen holds its page and the season it lists until the screen is cleared`() = fixture.viewModelTest {
        val server = RouteServer(this) { _, path, _ ->
            val id = path.removePrefix("/api/v1/library/items/")
            when {
                path == "/api/v1/library/home" -> 200 to """{"shelves":[]}"""
                id in setOf("show", "s1", "s2") -> 200 to """{"item":${item(id, "show")}}"""
                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()
        fun gets(id: String) = server.requests.count { it == "GET /api/v1/library/items/$id" }
        fun reopen(id: String) = model.openItem(id).use { runCurrent() }

        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { ItemPageViewModel(model::openItem, "show") } }
        val screen = ViewModelProvider.create(store, factory)[ItemPageViewModel::class]
        screen.season("s1")
        runCurrent()
        val season = screen.season("s2")
        runCurrent()
        assertIs<ItemDetailState.Ready>(screen.detail.value)
        assertIs<ItemDetailState.Ready>(season.value)

        reopen("s1")
        reopen("s2")
        assertEquals(listOf(1, 2, 1), listOf(gets("show"), gets("s1"), gets("s2")), "only the season listed stays")

        store.clear()
        reopen("show")
        reopen("s2")
        assertEquals(listOf(2, 2), listOf(gets("show"), gets("s2")), "a cleared screen lets its pages go")
    }
}
