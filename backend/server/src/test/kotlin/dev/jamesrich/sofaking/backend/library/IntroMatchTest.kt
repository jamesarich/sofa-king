package dev.jamesrich.sofaking.backend.library

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntroMatchTest {
    /** [seconds] of fingerprint points no other call shares, from [seed]. */
    private fun noise(seed: Int, seconds: Int) = Random(seed).let { r -> IntArray(points(seconds)) { r.nextInt() } }

    private fun points(seconds: Int) = (seconds * 1_000 / IntroMatch.POINT_MS).toInt()

    /** [points] with up to [bits] random bits of each point flipped, as a re-encode does. */
    private fun blurred(points: IntArray, seed: Int, bits: Int = 3) = Random(seed).let { r ->
        IntArray(points.size) { i -> (0 until r.nextInt(bits + 1)).fold(points[i]) { word, _ -> word xor (1 shl r.nextInt(32)) } }
    }

    private fun assertNear(expectedMs: Long, actualMs: Long) =
        assertTrue(abs(expectedMs - actualMs) <= 500, "expected about $expectedMs ms, was $actualMs ms")

    private val theme = noise(1, 45)

    @Test fun `two episodes share the theme where each plays it`() {
        val a = noise(10, 8) + theme + noise(11, 120)
        val b = blurred(noise(20, 30) + theme + noise(21, 120), seed = 2)
        val shared = assertNotNull(IntroMatch.shared(a, b))
        assertNear(8_000, shared.first.startMs)
        assertNear(53_000, shared.first.endMs)
        assertNear(30_000, shared.second.startMs)
        assertNear(75_000, shared.second.endMs)
    }

    @Test fun `episodes with nothing in common share nothing`() {
        assertNull(IntroMatch.shared(noise(30, 300), noise(31, 300)))
    }

    @Test fun `a shared stretch too short, too long or too sparse is no intro`() {
        val jingle = noise(40, 10)
        assertNull(IntroMatch.shared(noise(41, 20) + jingle + noise(42, 60), jingle + noise(43, 60)), "under 15 s")
        val episode = noise(44, 200)
        assertNull(IntroMatch.shared(episode, blurred(episode, seed = 3)), "the same file twice is over two minutes")
        // Every fourth point of the theme only: the matches are too far apart to be one stretch of the same sound.
        val sparse = theme.copyOf().also { for (i in it.indices) if (i % 4 != 0) it[i] = it[i].inv() }
        assertNull(IntroMatch.shared(noise(45, 10) + theme + noise(46, 60), noise(47, 5) + sparse + noise(48, 60)))
    }

    @Test fun `the intro is the span most others agree with, ending where most of them do`() {
        val spans = listOf(IntroSpan(0, 53_100), IntroSpan(0, 60_300), IntroSpan(0, 52_400), IntroSpan(33_800, 59_700))
        assertEquals(IntroSpan(0, 53_100), IntroMatch.intro(spans, minAgreeing = 2))
    }

    @Test fun `a stretch only one other episode shares is no intro when others could agree`() {
        val recap = listOf(IntroSpan(0, 40_000))
        assertNull(IntroMatch.intro(recap, minAgreeing = 2))
        assertEquals(IntroSpan(0, 40_000), IntroMatch.intro(recap, minAgreeing = 1))
        assertNull(IntroMatch.intro(emptyList(), minAgreeing = 1))
    }
}
