package dev.jamesrich.sofaking.backend.ads

import dev.jamesrich.sofaking.backend.store.openStore
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BreakLibraryTest {
    private var now = Instant.parse("2026-10-23T18:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val random = Random(7)
    private fun audio(points: Int) = IntArray(points) { random.nextInt() }
    /** [points] as another airing hears them: up to three bits of each word flipped. */
    private fun heardAgain(points: IntArray) = IntArray(points.size) { i -> (0 until random.nextInt(4)).fold(points[i]) { word, _ -> word xor (1 shl random.nextInt(32)) } }

    @Test
    fun `a learned break is found again in other audio, from where it starts in the entry`() {
        val library = BreakLibrary(null, clock)
        val ad = audio(240)
        library.learn(ad, "c1", "pluto", "clock:1")
        val query = audio(30) + heardAgain(ad.copyOfRange(50, 150)) + audio(20)
        val match = assertNotNull(library.match(query, BreakLibrary.MIN_LEARN_POINTS))
        assertEquals(30, match.queryFrom)
        assertEquals(129, match.queryTo)
        assertEquals(50, match.entryFrom)
        assertEquals(90, match.entryLeft)
        assertNull(library.match(audio(200), BreakLibrary.MIN_LEARN_POINTS), "other audio matches nothing")
        assertNull(library.match(heardAgain(ad.copyOfRange(0, 60)), BreakLibrary.MIN_LEARN_POINTS), "a stretch shorter than the least match")
        assertNull(library.match(query, BreakLibrary.MIN_LEARN_POINTS, exclude = "clock"), "a clock never matches what it learned")
    }

    @Test
    fun `known audio counts as marked again and may blank once on two channels, new audio becomes its own entry`() {
        val library = BreakLibrary(null, clock)
        val first = audio(200)
        val second = audio(150)
        library.learn(first, "c1", "pluto", "a:1")
        assertEquals(LibraryStats(1, 0, 0, 200, 0, 0, false), library.stats())
        library.learn(heardAgain(first) + second, "c1", "pluto", "a:2")
        assertEquals(2, library.stats().entries)
        assertEquals(0, library.stats().blanking, "the same channel the same day")
        library.learn(heardAgain(second), "c2", "pluto", "b:1")
        assertEquals(1, library.stats().blanking)
        now = now.plusSeconds(86_400)
        library.learn(heardAgain(first), "c1", "pluto", "a:3")
        assertEquals(2, library.stats().blanking, "a second day")
    }

    @Test
    fun `a false hit blocks its entry and freezes learned breaks until the canary is good for a day`() {
        val library = BreakLibrary(null, clock)
        val ad = audio(200)
        library.learn(ad, "c1", "pluto", "a:1")
        val entry = library.match(ad, BreakLibrary.MIN_LEARN_POINTS)!!.entry
        repeat(5) { library.hit(entry) }
        assertFalse(library.frozen())
        library.falseHit(entry)
        assertTrue(entry.blocked)
        assertTrue(library.frozen())
        assertEquals(LibraryStats(1, 0, 1, 200, 5, 1, true), library.stats())
        now = now.plusSeconds(86_401)
        assertFalse(library.frozen())
        assertTrue(library.match(ad, BreakLibrary.MIN_LEARN_POINTS)!!.entry.blocked, "a blocked entry still matches, to veto")
    }

    @Test
    fun `entries are saved, read back, and expire unheard`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-break-library-test-")
        try {
            openStore(dir).use { store ->
                val library = BreakLibrary(store, clock)
                val ad = audio(200)
                library.learn(ad, "c1", "pluto", "a:1")
                library.flush()
                val read = BreakLibrary(store, clock).also { it.load() }
                assertEquals(library.stats(), read.stats())
                val entry = assertNotNull(read.match(ad, BreakLibrary.MIN_LEARN_POINTS)).entry
                assertEquals(setOf("c1"), entry.channels)
                assertEquals("pluto", entry.providerId)
                now = now.plus(BreakLibrary.EXPIRY).plusSeconds(86_400 * 2)
                read.flush()
                assertEquals(0, read.stats().entries)
                assertEquals(0, BreakLibrary(store, clock).also { it.load() }.stats().entries)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
