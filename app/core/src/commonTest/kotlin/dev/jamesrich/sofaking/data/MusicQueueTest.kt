package dev.jamesrich.sofaking.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicQueueTest {
    private fun song(id: String) = MediaItem(id, "lib", ItemKind.TRACK, "Song $id")

    private val album = listOf(song("1"), song("2"), MediaItem("x", "lib", ItemKind.ALBUM, "Album"), song("3"))

    private fun MusicQueue.playing(): List<String> = generateSequence(this) { it.next(ended = true) }
        .take(10)
        .map { it.current.id }
        .toList()

    @Test
    fun `an album plays on from the song picked and ends after its last`() {
        val queue = MusicQueue.of(album, start = "2")!!
        assertEquals(listOf("2", "3"), queue.playing())
        assertFalse(queue.next()!!.hasNext)
        assertNull(MusicQueue.of(listOf(MediaItem("x", "lib", ItemKind.ALBUM, "Album"))))
    }

    @Test
    fun `previous steps back and stays on the first song`() {
        val queue = MusicQueue.of(album, start = "2")!!
        assertEquals("1", queue.previous().current.id)
        assertEquals("1", queue.previous().previous().current.id)
    }

    @Test
    fun `repeat goes round the whole queue or plays one song again`() {
        val all = MusicQueue.of(album)!!.nextRepeat()
        assertEquals(Repeat.ALL, all.repeat)
        assertEquals(listOf("1", "2", "3", "1"), all.playing().take(4))
        assertEquals("3", all.previous().current.id)
        val one = all.nextRepeat()
        assertEquals(Repeat.ONE, one.repeat)
        assertEquals(listOf("1", "1"), one.playing().take(2))
        assertEquals("2", one.next()?.current?.id, "a skip moves on")
        assertEquals(Repeat.OFF, one.nextRepeat().repeat)
    }

    @Test
    fun `shuffle plays every song once and starts from the one playing`() {
        val songs = (1..20).map { song("$it") }
        val shuffled = MusicQueue.of(songs, shuffled = true, random = Random(7))!!
        assertTrue(shuffled.shuffled)
        assertEquals(songs.indices.toSet(), shuffled.order.toSet())
        assertEquals(songs.size, shuffled.order.size)

        val picked = MusicQueue.of(songs, start = "5", shuffled = true, random = Random(7))!!
        assertEquals("5", picked.current.id)
        val back = picked.next()!!.shuffle(false)
        assertFalse(back.shuffled)
        assertEquals(picked.next()!!.current, back.current, "the song playing keeps playing")
        assertEquals(songs.indexOf(back.current), back.position, "and the rest follow in their own order")
    }
}
