package dev.jamesrich.sofaking.ui.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BreakScreenTest {
    @Test
    fun `the mark drifts without a hit inside the room`() {
        val (next, hit) = Bounce(
            10f,
            20f,
            right = true,
            down = false,
            tint = 2,
        ).step(5f, roomX = 100f, roomY = 100f, cornerSlop = 2f)
        assertEquals(Bounce(15f, 15f, right = true, down = false, tint = 2), next)
        assertEquals(BounceHit.NONE, hit)
    }

    @Test
    fun `an edge reflects the overshoot and moves the tint on`() {
        val (next, hit) = Bounce(
            98f,
            50f,
            right = true,
            down = true,
            tint = 0,
        ).step(5f, roomX = 100f, roomY = 100f, cornerSlop = 2f)
        assertEquals(Bounce(97f, 55f, right = false, down = true, tint = 1), next)
        assertEquals(BounceHit.EDGE, hit)
        val (left, leftHit) = Bounce(
            3f,
            50f,
            right = false,
            down = true,
            tint = 1,
        ).step(5f, roomX = 100f, roomY = 100f, cornerSlop = 2f)
        assertEquals(Bounce(2f, 55f, right = true, down = true, tint = 2), left)
        assertEquals(BounceHit.EDGE, leftHit)
    }

    @Test
    fun `edge hits cycle through every hue but the brand's own`() {
        var bounce = Bounce(0f, 50f, right = false, down = true, tint = 0)
        val tints = List(BOUNCE_HUES.size) {
            bounce = bounce.copy(x = 1f, right = false).step(5f, roomX = 100f, roomY = 100f, cornerSlop = 0f).first
            bounce.tint
        }
        assertEquals(listOf(1, 2, 3, 4, 5, 1), tints)
    }

    @Test
    fun `both edges in one step is a corner and brings the brand colors back`() {
        val (next, hit) = Bounce(
            98f,
            198f,
            right = true,
            down = true,
            tint = 4,
        ).step(5f, roomX = 100f, roomY = 200f, cornerSlop = 0f)
        assertEquals(Bounce(97f, 197f, right = false, down = false, tint = 0), next)
        assertEquals(BounceHit.CORNER, hit)
    }

    @Test
    fun `an edge hit within the slop of the other edge counts as a corner`() {
        val (_, near) = Bounce(
            98f,
            4f,
            right = true,
            down = false,
            tint = 3,
        ).step(1f, roomX = 100f, roomY = 100f, cornerSlop = 6f)
        assertEquals(BounceHit.NONE, near, "no edge reached yet")
        val (next, corner) = Bounce(
            99f,
            4f,
            right = true,
            down = false,
            tint = 3,
        ).step(2f, roomX = 100f, roomY = 100f, cornerSlop = 6f)
        assertEquals(BounceHit.CORNER, corner)
        assertEquals(0, next.tint)
        val (_, edge) = Bounce(
            99f,
            40f,
            right = true,
            down = false,
            tint = 3,
        ).step(2f, roomX = 100f, roomY = 100f, cornerSlop = 6f)
        assertEquals(BounceHit.EDGE, edge, "far from the other edge")
    }

    @Test
    fun `no room pins the mark at the start without hits`() {
        val (next, hit) = Bounce(0f, 0f, right = true, down = true).step(30f, roomX = 0f, roomY = -10f, cornerSlop = 6f)
        assertEquals(Bounce(0f, 0f, right = true, down = true), next)
        assertEquals(BounceHit.NONE, hit)
    }

    @Test
    fun `a step longer than the room stays inside it`() {
        val (next, _) = Bounce(
            90f,
            50f,
            right = true,
            down = true,
        ).step(500f, roomX = 100f, roomY = 100f, cornerSlop = 0f)
        assertEquals(0f, next.x)
        assertEquals(0f, next.y)
    }

    @Test
    fun `a zero hue turn leaves the colors as they are`() {
        val identity = hueRotation(0f).values
        val expected = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        expected.forEachIndexed { i, v -> assertEquals(v, identity[i], 0.001f, "entry $i") }
    }

    @Test
    fun `the deck deals every line once before any comes back`() {
        val deck = Deck(5, Random(7))
        val first = List(5) { deck.next() }
        assertEquals((0 until 5).toSet(), first.toSet())
        val second = List(5) { deck.next() }
        assertEquals((0 until 5).toSet(), second.toSet())
    }

    @Test
    fun `the deck never deals the same line twice running`() {
        repeat(50) { seed ->
            val deck = Deck(3, Random(seed))
            val dealt = List(30) { deck.next() }
            dealt.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b, "seed $seed: $dealt") }
        }
    }

    @Test
    fun `a deck of one deals it every time`() {
        val deck = Deck(1)
        assertEquals(listOf(0, 0, 0), List(3) { deck.next() })
    }
}
