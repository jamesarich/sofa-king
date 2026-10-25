package dev.jamesrich.sofaking.backend.virtual

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleBuilderTest {
    private val start = Instant.parse("2026-10-25T18:00:00Z")
    private val utc = ZoneOffset.UTC

    private fun item(id: String, minutes: Long) = PoolItem(id, Duration.ofMinutes(minutes).toMillis())

    private fun builder(
        slots: List<ScheduleSlot>,
        filler: List<PoolItem> = emptyList(),
        pad: Int = 0,
        history: List<Airing> = emptyList(),
    ) = ScheduleBuilder(slots, filler, pad, utc, history, Random(7))

    private fun List<Airing>.contiguous() = zipWithNext().all { (a, b) -> a.endsAt == b.startsAt }

    @Test fun `untimed slots take turns and each plays its items in order`() {
        val shows = ScheduleSlot(0, listOf(item("a1", 30), item("a2", 30)), shuffle = false)
        val movies = ScheduleSlot(1, listOf(item("m1", 90)), shuffle = false)
        val until = start.plus(Duration.ofMinutes(270))
        val airings = builder(listOf(shows, movies)).extend(start, until)
        assertEquals(listOf("a1", "m1", "a2", "m1", "a1"), airings.map { it.itemId })
        assertEquals(listOf(0, 1, 0, 1, 0), airings.map { it.slot })
        assertTrue(airings.contiguous())
        assertEquals(until, airings.last().endsAt)
    }

    @Test fun `the order carries on from what aired before`() {
        val slot = ScheduleSlot(0, listOf(item("e1", 30), item("e2", 30), item("e3", 30)), shuffle = false)
        val history = listOf(Airing(start.minus(Duration.ofMinutes(30)), start, "e2", 0))
        val airings = builder(listOf(slot), history = history).extend(start, start.plus(Duration.ofMinutes(60)))
        assertEquals(listOf("e3", "e1"), airings.map { it.itemId })
    }

    @Test fun `a shuffle plays every item once before any again`() {
        val items = (1..6).map { item("i$it", 10) }
        val airings = builder(listOf(ScheduleSlot(0, items, shuffle = true)))
            .extend(start, start.plus(Duration.ofMinutes(60)))
        assertEquals(items.map { it.id }.toSet(), airings.take(6).map { it.itemId }.toSet())
    }

    @Test fun `padding fills to the next mark with filler that fits, then a blank`() {
        val slot = ScheduleSlot(0, listOf(item("show", 22)), shuffle = false)
        val bumpers = listOf(item("bump", 3))
        val airings = builder(listOf(slot), bumpers, pad = 30).extend(start, start.plus(Duration.ofMinutes(30)))
        assertEquals(listOf("show", "bump", "bump", null), airings.map { it.itemId })
        assertEquals(listOf(false, true, true, false), airings.map { it.filler })
        assertEquals(start.plus(Duration.ofMinutes(30)), airings.last().endsAt)
        assertNull(airings.last().slot)
        assertTrue(airings.contiguous())
    }

    @Test fun `timed slots run from their start to the next and skip what does not fit`() {
        val morning = ScheduleSlot(0, listOf(item("cartoon", 25)), shuffle = false, startMinute = 18 * 60)
        val evening = ScheduleSlot(1, listOf(item("movie", 100)), shuffle = false, startMinute = 19 * 60)
        val airings = builder(listOf(morning, evening)).extend(start, start.plus(Duration.ofHours(2)))
        val items = airings.filter { it.itemId != null }
        assertEquals(listOf("cartoon", "cartoon", "movie"), items.map { it.itemId })
        val gap = airings.first { it.itemId == null }
        assertEquals(Instant.parse("2026-10-25T18:50:00Z"), gap.startsAt)
        assertEquals(Instant.parse("2026-10-25T19:00:00Z"), gap.endsAt)
        assertEquals(Instant.parse("2026-10-25T19:00:00Z"), items.last().startsAt)
    }

    @Test fun `the first item of a timed slot airs even when it runs past the next start`() {
        val short = ScheduleSlot(0, listOf(item("movie", 100)), shuffle = false, startMinute = 18 * 60)
        val next = ScheduleSlot(1, listOf(item("news", 30)), shuffle = false, startMinute = 19 * 60)
        val airings = builder(listOf(short, next)).extend(start, start.plus(Duration.ofHours(3)))
        assertEquals("movie", airings[0].itemId)
        assertEquals("news", airings[1].itemId, "the next slot starts late")
        assertEquals(Instant.parse("2026-10-25T19:40:00Z"), airings[1].startsAt)
    }

    @Test fun `a live slot airs its channel for the whole window, from when the slot before it ends`() {
        val movies = ScheduleSlot(0, listOf(item("movie", 100)), shuffle = false, startMinute = 18 * 60)
        val news = ScheduleSlot(1, emptyList(), shuffle = false, startMinute = 19 * 60, live = liveAiringId("news"))
        val airings = builder(listOf(movies, news)).extend(start, start.plus(Duration.ofHours(3)))
        val live = airings.filter { liveEntryOf(it.itemId) == "news" }
        assertEquals(Instant.parse("2026-10-25T19:40:00Z"), live.first().startsAt, "the movie runs on")
        assertEquals(Instant.parse("2026-10-26T18:00:00Z"), live.first().endsAt)
        assertEquals(1, live.first().slot)
        assertTrue(airings.contiguous())
    }

    @Test fun `a break airs its item and slides after an item once it is due`() {
        val slot = ScheduleSlot(0, listOf(item("show", 10)), shuffle = false)
        val plan =
            BreakPlan(Duration.ofMinutes(15).toMillis(), listOf(item("bumper", 1)), listOf(slideAiringId(0)), 30_000)
        val airings = ScheduleBuilder(listOf(slot), emptyList(), 0, utc, emptyList(), Random(7), plan)
            .extend(start, start.plus(Duration.ofMinutes(40)))
        assertEquals(
            listOf("show", "bumper", "slide:0", "show", "show", "bumper", "slide:0", "show"),
            airings.map { it.itemId },
            "a break, then none until 15 minutes have passed",
        )
        assertTrue(airings.filter { it.slot == ScheduleBuilder.BREAK_SLOT }.all { it.filler })
        assertEquals(30_000, Duration.between(airings[2].startsAt, airings[2].endsAt).toMillis())
        assertTrue(airings.contiguous())
    }

    @Test fun `a break that does not fit before the next timed slot is left out`() {
        val first = ScheduleSlot(0, listOf(item("show", 29)), shuffle = false, startMinute = 18 * 60)
        val next = ScheduleSlot(1, listOf(item("news", 30)), shuffle = false, startMinute = 18 * 60 + 30)
        val plan = BreakPlan(0, listOf(item("bumper", 2)), emptyList(), 0)
        val airings = ScheduleBuilder(listOf(first, next), emptyList(), 0, utc, emptyList(), Random(7), plan)
            .extend(start, start.plus(Duration.ofMinutes(30)))
        assertEquals(listOf("show", null), airings.map { it.itemId })
    }

    @Test fun `with nothing to air the schedule is blank`() {
        val airings = builder(listOf(ScheduleSlot(0, emptyList(), shuffle = false)))
            .extend(start, start.plus(Duration.ofHours(1)))
        assertEquals(listOf(null, null), airings.map { it.itemId })
        assertTrue(airings.contiguous())
    }

    @Test fun `a gap too short for a blank stretches the airing before it`() {
        val slot = ScheduleSlot(0, listOf(PoolItem("clip", Duration.ofMinutes(15).minusSeconds(1).toMillis())), false)
        val airings = builder(listOf(slot), pad = 15).extend(start, start.plus(Duration.ofMinutes(15)))
        assertEquals(1, airings.size)
        assertEquals(start.plus(Duration.ofMinutes(15)), airings.single().endsAt)
    }
}
