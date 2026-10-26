package dev.jamesrich.sofaking.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayQueueTest {
    private fun song(id: String) = MediaItem(id, "lib", ItemKind.TRACK, "Song $id")

    @Test
    fun `an album plays on in its order and ends after its last song`() {
        val queue = PlayQueue.of(
            listOf(song("1"), song("2"), MediaItem("x", "lib", ItemKind.ALBUM, "Album"), song("3")),
        )
        assertEquals(listOf("1", "2", "3"), queue.items.map { it.id })
        assertEquals("2", queue.after("1")?.id)
        assertNull(queue.after("3"))
        assertNull(queue.after("missing"))
    }

    @Test
    fun `shuffle plays every song once in a random order`() {
        val songs = (1..20).map { song("$it") }
        val queue = PlayQueue.of(songs, shuffled = true, random = Random(7))
        assertEquals(songs.toSet(), queue.items.toSet())
        assertEquals(songs.size, queue.items.size)
        assertEquals(songs.shuffled(Random(7)), queue.items)
    }
}
