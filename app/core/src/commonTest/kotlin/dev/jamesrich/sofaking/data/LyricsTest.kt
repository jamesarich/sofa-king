package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsTest {
    @Test
    fun `LRC lines are timed and repeated tags sing a line twice while the offset moves them`() {
        val lyrics = parseLrc(
            "[ar:Band]\n[ti:One]\n[offset:+500]\n[00:12.30]First line\n[01:02.5][00:20.00] Chorus\n[00:15.123]\n",
        )
        assertTrue(lyrics.synced)
        assertEquals(
            listOf(
                LyricLine(11_800, "First line"),
                LyricLine(14_623, ""),
                LyricLine(19_500, "Chorus"),
                LyricLine(62_000, "Chorus"),
            ),
            lyrics.lines,
        )
        assertEquals(-1, lyrics.lineAt(5_000))
        assertEquals(0, lyrics.lineAt(11_800))
        assertEquals(2, lyrics.lineAt(30_000))
        assertEquals(3, lyrics.lineAt(400_000))
    }

    @Test
    fun `lyrics without time tags are plain lines with their verses`() {
        val lyrics = parseLrc("\nVerse one\nstill one\n\nVerse two\n\n")
        assertFalse(lyrics.synced)
        assertEquals(listOf("Verse one", "still one", "", "Verse two"), lyrics.lines.map { it.text })
        assertEquals(-1, lyrics.lineAt(10_000))
    }
}
