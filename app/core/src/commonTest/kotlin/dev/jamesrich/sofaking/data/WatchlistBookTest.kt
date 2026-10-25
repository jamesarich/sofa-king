package dev.jamesrich.sofaking.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistBookTest {
    private val news =
        Program(
            "c1",
            Instant.parse("2026-10-18T18:00:00Z"),
            Instant.parse("2026-10-18T18:30:00Z"),
            "Evening News",
            null,
        )
    private val movie = MediaItem("m1", "lib", ItemKind.MOVIE, "Alien")

    private val list = """{"entries":[
        {"id":"w1","kind":"title","title":" evening news "},
        {"id":"w2","kind":"title","title":"Film","channelId":"c2"},
        {"id":"w3","kind":"item","title":"Alien","itemId":"m1"}
    ]}"""

    @Test
    fun `the list marks its items and titles and a title on one channel only there`() = runTest {
        val http = FakeHttp(this) { json(list) }
        val book = WatchlistBook(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        val marks = book.marks.value
        assertTrue(marks.offered)
        assertTrue(marks.has(movie))
        assertFalse(marks.has(movie.copy(id = "m2")))
        assertTrue(marks.has(news))
        assertTrue(marks.has(news.copy(channelId = "c9")), "a title on any channel")
        val film = news.copy(title = "Film")
        assertFalse(marks.has(film))
        assertTrue(marks.has(film.copy(channelId = "c2")))
    }

    @Test
    fun `toggling adds an entry and then takes every entry for the title off`() = runTest {
        var listed = false
        val http = FakeHttp(this) { seen ->
            when (seen.method) {
                "POST" -> {
                    listed = true
                    json("""{"id":"w9","kind":"title","title":"Evening News"}""", 201)
                }

                "DELETE" -> {
                    listed = false
                    json("")
                }

                else -> json(if (listed) LISTED else "{}")
            }
        }
        val book = WatchlistBook(backgroundScope, flowOf(http.api()), flowOf(true))
        runCurrent()

        assertTrue(book.toggleTitle(news))
        assertTrue(book.marks.value.has(news))
        assertTrue(book.toggleTitle(news))
        assertFalse(book.marks.value.has(news))
        assertTrue(book.toggleItem("m1"))
        assertEquals(
            listOf(
                """POST /api/v1/watchlist {"kind":"title","title":"Evening News"}""",
                "DELETE /api/v1/watchlist/w9",
                """POST /api/v1/watchlist {"kind":"item","itemId":"m1"}""",
            ),
            http.requests.filter { it.method != "GET" }.map { it.toString() },
        )
    }

    @Test
    fun `a server without a watchlist is never asked and offers none`() = runTest {
        val http = FakeHttp(this) { json("{}") }
        val enabled = MutableStateFlow(false)
        val book = WatchlistBook(backgroundScope, flowOf(http.api()), enabled)
        runCurrent()

        assertFalse(book.toggleItem("m1"))
        assertFalse(book.marks.value.offered)
        assertTrue(http.requests.isEmpty())
        enabled.value = true
        runCurrent()
        assertEquals(listOf("GET /api/v1/watchlist"), http.requests.map { it.toString() })
        assertTrue(book.marks.value.offered)
    }
}

private const val LISTED = """{"entries":[{"id":"w9","kind":"title","title":"Evening News"}]}"""
