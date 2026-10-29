package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrailerDwellTest {
    /** A clock the test advances by hand. */
    private var now = 1_000L
    private val dwell = TrailerDwell()

    private fun advance(ms: Long) {
        now += ms
    }

    private fun due(playable: Boolean = true, inFront: Boolean = true, held: Boolean = false, music: Boolean = false) =
        dwell.isDue(now, playable, inFront, held, music)

    @Test
    fun `a page that rests three seconds with nothing playing is due`() {
        dwell.restart(now)
        advance(2_999)
        assertFalse(due())
        advance(1)
        assertTrue(due())
    }

    @Test
    fun `any key or touch restarts the rest`() {
        dwell.restart(now)
        advance(2_000)
        dwell.onInput(now)
        advance(2_999)
        assertFalse(due())
        advance(1)
        assertTrue(due())
    }

    @Test
    fun `an input read late restarts the rest from when it happened and never from before the page rested`() {
        dwell.restart(now)
        advance(2_000)
        dwell.onInput(now - 1_500)
        advance(1_499)
        assertFalse(due())
        advance(1)
        assertTrue(due())
        dwell.restart(now)
        dwell.onInput(now - 10_000)
        advance(3_000)
        assertTrue(due())
    }

    @Test
    fun `the next check is the time the rest has left`() {
        dwell.restart(now)
        advance(1_000)
        assertEquals(2_000L, dwell.nextCheckMs(now))
        advance(5_000)
        assertEquals(0L, dwell.nextCheckMs(now))
    }

    @Test
    fun `a held session of any kind keeps the trailer from starting`() {
        dwell.restart(now)
        advance(10_000)
        assertFalse(due(held = true))
        assertTrue(due(held = false))
    }

    @Test
    fun `a song queued or held keeps the trailer from starting`() {
        dwell.restart(now)
        advance(10_000)
        assertFalse(due(music = true))
        assertTrue(due(music = false))
    }

    @Test
    fun `a page behind another or an app in the background is never due`() {
        dwell.restart(now)
        advance(10_000)
        assertFalse(due(inFront = false))
        assertTrue(due(inFront = true))
    }

    @Test
    fun `a page without a trailer is never due`() {
        dwell.restart(now)
        advance(10_000)
        assertFalse(due(playable = false))
    }

    @Test
    fun `a playing trailer is not restarted by input and is not due again`() {
        dwell.restart(now)
        advance(3_000)
        dwell.started()
        assertEquals(TrailerPhase.PLAYING, dwell.phase)
        dwell.onInput(now)
        advance(10_000)
        assertFalse(due())
        assertNull(dwell.nextCheckMs(now))
    }

    @Test
    fun `a trailer stopped for the background rests again from the return`() {
        dwell.restart(now)
        advance(3_000)
        dwell.started()
        advance(4_000)
        dwell.stopped(now)
        assertEquals(TrailerPhase.RESTING, dwell.phase)
        advance(2_999)
        assertFalse(due())
        advance(1)
        assertTrue(due())
    }

    @Test
    fun `a trailer that finished or lost the player does not start again`() {
        dwell.restart(now)
        advance(3_000)
        dwell.started()
        dwell.finished()
        advance(60_000)
        assertFalse(due())
        dwell.stopped(now)
        assertEquals(TrailerPhase.DONE, dwell.phase)
        assertFalse(due())
    }

    @Test
    fun `a page whose trailer already played starts done`() {
        val replay = TrailerDwell(done = true)
        replay.restart(now)
        advance(60_000)
        assertFalse(replay.isDue(now, playable = true, inFront = true, held = false, music = false))
    }

    @Test
    fun `stopping a trailer that never started changes nothing`() {
        dwell.stopped(now)
        assertEquals(TrailerPhase.RESTING, dwell.phase)
    }

    private fun item(id: String, kind: ItemKind, extra: ExtraKind? = null, rating: String? = null) =
        MediaItem(id = id, libraryId = "l", kind = kind, title = id, extra = extra, contentRating = rating)

    @Test
    fun `a movie or show page plays its first trailer and not other extras`() {
        val featurette = item("f", ItemKind.EXTRA, ExtraKind.FEATURETTE)
        val trailer = item("t", ItemKind.EXTRA, ExtraKind.TRAILER)
        val other = item("t2", ItemKind.EXTRA, ExtraKind.TRAILER)
        val movie = MediaItemDetail(item("m", ItemKind.MOVIE), extras = listOf(featurette, trailer, other))
        assertSame(trailer, trailerOf(movie, kids = false))
        val show = MediaItemDetail(item("s", ItemKind.SHOW), extras = listOf(trailer))
        assertSame(trailer, trailerOf(show, kids = false))
        assertNull(trailerOf(MediaItemDetail(item("m", ItemKind.MOVIE), extras = listOf(featurette)), kids = false))
    }

    @Test
    fun `an episode season or music page plays no trailer`() {
        val trailer = item("t", ItemKind.EXTRA, ExtraKind.TRAILER)
        for (kind in listOf(ItemKind.EPISODE, ItemKind.SEASON, ItemKind.ALBUM, ItemKind.VIDEO)) {
            assertNull(trailerOf(MediaItemDetail(item("x", kind), extras = listOf(trailer)), kids = false), "$kind")
        }
    }

    @Test
    fun `kids mode plays the trailer only of an item it allows`() {
        val trailer = item("t", ItemKind.EXTRA, ExtraKind.TRAILER)
        val allowed = MediaItemDetail(item("a", ItemKind.MOVIE, rating = "PG"), extras = listOf(trailer))
        val unrated = MediaItemDetail(item("u", ItemKind.MOVIE), extras = listOf(trailer))
        val adult = MediaItemDetail(item("r", ItemKind.MOVIE, rating = "R"), extras = listOf(trailer))
        assertSame(trailer, trailerOf(allowed, kids = true))
        assertNull(trailerOf(unrated, kids = true))
        assertNull(trailerOf(adult, kids = true))
        assertSame(trailer, trailerOf(adult, kids = false))
    }
}
