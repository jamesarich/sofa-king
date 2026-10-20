package dev.jamesrich.sofaking.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class NewestMediaSessionTest {
    private val events = mutableListOf<String>()
    private var opened = 0
    private val sessions = NewestMediaSession { _ ->
        val name = "s${++opened}"
        events += "open $name"
        object : MediaSessionHandle {
            override fun publish(nowPlaying: NowPlaying) {
                events += "publish $name ${nowPlaying.title}"
            }

            override fun close() {
                events += "close $name"
            }
        }
    }

    private fun program(title: String) = NowPlaying(title, subtitle = "", artworkUrl = null)

    @Test
    fun `a newer page takes the session and the older page's close does nothing`() {
        val old = sessions.open {}
        old.publish(program("A"))
        val new = sessions.open {}
        new.publish(program("B"))
        old.publish(program("stale"))
        old.close()
        new.close()
        assertEquals(listOf("open s1", "publish s1 A", "close s1", "open s2", "publish s2 B", "close s2"), events)
    }

    @Test
    fun `closing twice closes the session once and a reopen starts a fresh one`() {
        val first = sessions.open {}
        first.close()
        first.close()
        sessions.open {}.close()
        assertEquals(listOf("open s1", "close s1", "open s2", "close s2"), events)
    }

    @Test
    fun `each session zaps through its own page's callback`() {
        val steps = mutableListOf<String>()
        val zapping = NewestMediaSession { onZapStep ->
            onZapStep(1)
            object : MediaSessionHandle {
                override fun publish(nowPlaying: NowPlaying) = Unit

                override fun close() = Unit
            }
        }
        zapping.open { steps += "old $it" }
        zapping.open { steps += "new $it" }
        assertEquals(listOf("old 1", "new 1"), steps)
    }
}
