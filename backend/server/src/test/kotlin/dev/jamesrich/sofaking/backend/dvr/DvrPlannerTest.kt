package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.PreviouslyShown
import dev.jamesrich.sofaking.contract.Wire
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class DvrPlannerTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private fun at(hours: Long) = now.plus(Duration.ofHours(hours))

    private fun rule(title: String = "Star Trek", channel: String? = null, newOnly: Boolean = true, priority: Int = 0, id: String = "rule-$title-$priority") =
        StoredRule(id, title, channel, newOnly, 0, Duration.ofMinutes(1), Duration.ofMinutes(2), priority, now)

    private fun airing(
        hours: Long, title: String = "Star Trek", channel: String = "a", episode: String? = null, subTitle: String? = null, repeat: Boolean = false,
    ) = Airing(
        channel, channel.uppercase(),
        GuideProgramme(
            channel, at(hours), at(hours).plus(Duration.ofHours(1)), title, subTitle = subTitle,
            episodeNumbers = listOfNotNull(episode?.let { EpisodeNumber("onscreen", it) }), previouslyShown = if (repeat) PreviouslyShown() else null,
        ),
    )

    private fun recording(
        id: String, from: Instant, hours: Long = 1, ruleId: String? = null, state: String = Wire.RecordingState.SCHEDULED, channel: String = "a",
        title: String = "Star Trek", season: Int? = null, episode: Int? = null,
    ) = StoredRecording(id, ruleId, channel, channel, from, from.plus(Duration.ofHours(hours)), Duration.ZERO, Duration.ZERO, title,
        season = season, episode = episode, state = state, createdAt = now, updatedAt = now)

    @Test fun `a rule takes airings of its title on its channel, ignoring case and repeats`() {
        val airings = listOf(airing(1), airing(2, title = "STAR TREK "), airing(3, channel = "b"), airing(4, repeat = true), airing(5, title = "Other"))
        val plan = DvrPlanner.plan(listOf(rule(channel = "a")), airings, emptyList(), now)
        assertEquals(listOf(at(1), at(2)), plan.add.map { it.second.programme.startsAt })
        assertEquals(4, DvrPlanner.plan(listOf(rule(newOnly = false)), airings, emptyList(), now).add.size)
    }

    @Test fun `an episode already held or picked once is not scheduled again`() {
        val airings = listOf(airing(1, episode = "S01E01"), airing(2, channel = "b", episode = "S01E01"), airing(3, episode = "S01E02"), airing(4, subTitle = "Pilot"), airing(5, subTitle = "pilot"))
        val held = listOf(recording("kept", at(-48), state = Wire.RecordingState.COMPLETED, season = 1, episode = 2))
        val plan = DvrPlanner.plan(listOf(rule(newOnly = false)), airings, held, now)
        assertEquals(listOf(at(1), at(4)), plan.add.map { it.second.programme.startsAt })
    }

    @Test fun `an airing without numbering or an episode title is recorded every time`() {
        val plan = DvrPlanner.plan(listOf(rule("News")), listOf(airing(1, "News"), airing(25, "News")), emptyList(), now)
        assertEquals(2, plan.add.size)
    }

    @Test fun `a slot with any recording, even a cancelled one, and an ended airing are skipped`() {
        val cancelled = recording("x", at(1), ruleId = "rule-Star Trek-0", state = CANCELLED)
        val plan = DvrPlanner.plan(listOf(rule()), listOf(airing(-2), airing(1), airing(2)), listOf(cancelled), now)
        assertEquals(listOf(at(2)), plan.add.map { it.second.programme.startsAt })
    }

    @Test fun `a scheduled rule airing the guide no longer has is dropped, one-offs and started ones are not`() {
        val moved = recording("moved", at(3), ruleId = "r")
        val oneOff = recording("one-off", at(3), channel = "c")
        val soon = recording("soon", now.minusSeconds(30), ruleId = "r")
        val plan = DvrPlanner.plan(listOf(rule(id = "r")), listOf(airing(5)), listOf(moved, oneOff, soon), now)
        assertEquals(listOf("moved"), plan.drop.map { it.id })
    }

    @Test fun `recordings beyond the slots conflict, lowest rank first`() {
        val oneOff = recording("one-off", at(1))
        val high = recording("high", at(1), ruleId = "high", channel = "b")
        val low = recording("low", at(1), ruleId = "low", channel = "c")
        val later = recording("later", at(2), ruleId = "low", channel = "d")
        val priorities = mapOf("high" to 5, "low" to 0)
        assertEquals(setOf("low"), DvrPlanner.conflicts(listOf(low, high, oneOff, later), priorities, capacity = 2))
        assertEquals(setOf("high", "low"), DvrPlanner.conflicts(listOf(low, high, oneOff, later), priorities, capacity = 1))
        assertEquals(emptySet(), DvrPlanner.conflicts(listOf(low, high, oneOff, later), priorities, capacity = 3))
    }

    @Test fun `a running recording keeps its slot against a higher-ranked scheduled one`() {
        val running = recording("running", now.minusSeconds(600), ruleId = "low", state = Wire.RecordingState.RECORDING)
        val oneOff = recording("one-off", now.plusSeconds(60))
        assertEquals(setOf("one-off"), DvrPlanner.conflicts(listOf(oneOff, running), mapOf("low" to 0), capacity = 1))
    }

    @Test fun `a rule keeps its newest recordings, protected ones count but stay, and age culls the rest`() {
        val keepTwo = rule().copy(id = "r", keep = 2)
        val done = Wire.RecordingState.COMPLETED
        val recordings = listOf(
            recording("new", at(-24), ruleId = "r", state = done), recording("mid", at(-48), ruleId = "r", state = done).copy(protected = true),
            recording("old", at(-72), ruleId = "r", state = done), recording("ancient", at(-24 * 40), state = done),
            recording("ancient-kept", at(-24 * 40), state = done).copy(protected = true), recording("soon", at(1)),
        )
        val cull = DvrPlanner.cull(recordings, mapOf("r" to keepTwo), Retention(keepDays = 30), now)
        assertEquals(listOf("old" to "KEEP", "ancient" to "AGE"), cull.map { it.first.id to it.second })
        assertEquals(listOf("old" to "KEEP"), DvrPlanner.cull(recordings, mapOf("r" to keepTwo), Retention(), now).map { it.first.id to it.second })
    }

    @Test fun `space runs out on watched recordings first, then the oldest`() {
        val done = Wire.RecordingState.COMPLETED
        val order = DvrPlanner.byCullOrder(listOf(
            recording("old", at(-72), state = done), recording("watched", at(-24), state = done).copy(watched = true),
            recording("older", at(-96), state = done), recording("kept", at(-200), state = done).copy(protected = true), recording("soon", at(1)),
        ))
        assertEquals(listOf("watched", "older", "old"), order.map { it.id })
    }
}
