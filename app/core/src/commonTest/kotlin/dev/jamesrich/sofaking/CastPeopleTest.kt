package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.CastPerson
import dev.jamesrich.sofaking.data.LibraryRepository
import dev.jamesrich.sofaking.data.billed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** An item's cast as its page lists them: with roles and photos where the server sends them, the names otherwise. */
class CastPeopleTest {
    private val movie =
        """{"id":"m1","libraryId":"movies","kind":"movie","title":"Alien","addedAt":"2026-10-10T13:00:00Z"}"""

    @Test
    fun `the cast carry their roles, and their photos at the server's address`() = runTest {
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/items/m1" ->
                    200 to """{"item":$movie,"cast":["Ana Reyes","Tom Hale"],"people":[
                    {"name":"Ana Reyes","role":"Dr. Stone","photo":"/artwork/m1/cast/0?v=1"},{"name":"Tom Hale"}]}"""

                else -> null
            }
        }
        val detail = LibraryRepository(server.http.api()).detail("m1")
        val photo = detail.people.first().photo
        assertEquals(listOf("Ana Reyes", "Tom Hale"), detail.cast)
        assertEquals(listOf("Dr. Stone", null), detail.people.map { it.role })
        assertEquals(true, photo?.startsWith("http") == true && photo.endsWith("/artwork/m1/cast/0?v=1"), photo)
        assertEquals(null, detail.people.last().photo)
        assertEquals(detail.people, detail.billed)
    }

    @Test
    fun `a server that sends only names lists those`() = runTest {
        val server = RouteServer(this) { _, path, _ ->
            when (path) {
                "/api/v1/library/items/m1" -> 200 to """{"item":$movie,"cast":["Ana Reyes","Tom Hale"]}"""
                else -> null
            }
        }
        val detail = LibraryRepository(server.http.api()).detail("m1")
        assertEquals(emptyList(), detail.people)
        assertEquals(listOf(CastPerson("Ana Reyes"), CastPerson("Tom Hale")), detail.billed)
    }
}
