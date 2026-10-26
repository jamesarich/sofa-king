package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.PlayQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The song [LibraryViewModel.upNext] plays after another: its queue's next, else its album's. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicQueueTest {
    private val fixture = ViewModelFixture()

    @AfterTest
    fun tearDown() = fixture.tearDown()

    private fun item(id: String, kind: String, extra: String = "") =
        """{"id":"$id","libraryId":"lib","kind":"$kind","title":"Title $id","addedAt":"2026-10-10T13:00:00Z"$extra}"""

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
    fun `a song goes on to the next in its queue else in its album`() = fixture.viewModelTest {
        fun track(id: String) = item(id, "track", ""","parentId":"a1"""")
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/home" -> 200 to """{"shelves":[]}"""

                "/api/v1/library/items/a1" ->
                    200 to
                        """{"item":${item("a1", "album")},"children":[${track("t1")},${track("t2")},${track("t3")}]}"""

                else -> null
            }
        }
        val model = viewModel(server)
        runCurrent()
        fun song(id: String) = MediaItem(id, "lib", ItemKind.TRACK, "Title $id", parentId = "a1")

        assertEquals("t2", model.upNext(song("t1"))?.id, "the album's order without a queue")
        assertEquals(null, model.upNext(song("t3")), "nothing after the album's last song")
        model.queue(PlayQueue(listOf(song("t3"), song("t1"), song("t2"))))
        assertEquals("t1", model.upNext(song("t3"))?.id, "the queue's order")
        assertEquals(null, model.upNext(song("t2")), "nothing after the queue's last song")
        model.queue(null)
        assertEquals("t3", model.upNext(song("t2"))?.id)
    }
}
