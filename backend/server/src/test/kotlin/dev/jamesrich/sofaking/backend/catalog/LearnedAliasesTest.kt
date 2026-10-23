package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyNames
import dev.jamesrich.sofaking.backend.api.PolicyNumbering
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.api.StationOverride
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.contract.Channel
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearnedAliasesTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z")

    private fun candidate(provider: String, id: String, name: String, number: String, offerings: Int = 1) =
        FeedCandidate(
            Channel(id, number, name, null, emptyList(), id),
            provider,
            (1..offerings).map { Offering("$id-o$it", id, provider, "https://example.test/$id/$it", "hls") },
        )

    private fun build(policy: LineupPolicy, candidates: List<FeedCandidate>): BuildResult = StationBuilder.build(
        BuildInput(
            policy,
            candidates,
            {
                emptyList()
            },
            setOf("whale", "samsung"),
            candidates.flatMap { it.offerings }.associate {
                it.id to
                    HealthState(HealthStatus.HEALTHY)
            },
            emptyMap(),
            now,
        ),
    )

    private fun schedule(vararg titles: String, offsetMinutes: Long = 0) = titles.mapIndexed { hour, title ->
        val start = now.plus(Duration.ofHours(hour.toLong())).plus(Duration.ofMinutes(offsetMinutes))
        GuideProgramme("x", start, start.plus(Duration.ofHours(1)), title)
    }

    private val policy = LineupPolicy(providers = listOf(PolicyProvider("whale"), PolicyProvider("samsung")))
    private val catalog = listOf(
        candidate("whale", "w1", "Ghost Hunters", "1", offerings = 2),
        candidate("samsung", "s1", "Ghost Hunters Channel", "101"),
        candidate("whale", "w2", "NFL Network", "2"),
        candidate("samsung", "s2", "NFL Channel", "102"),
        candidate("whale", "w3", "Modern Marvels", "3"),
        candidate("samsung", "s3", "Modern Marvels by HISTORY", "103"),
    )
    private val hunts = schedule("Ghost Hunters", "Ghost Hunters", "Ghost Hunters: Live!", "Paranormal Lockdown")
    private val schedules = mapOf(
        "ghosthunters" to hunts,
        "ghosthunterschannel" to
            schedule(
                "GHOST HUNTERS",
                "Ghost Hunters",
                "Ghost Hunters - Live",
                "Paranormal Lockdown",
                offsetMinutes = 4,
            ),
        "nflnetwork" to schedule("NFL Total Access", "Good Morning Football", "NFL Films Presents", "Inside the NFL"),
        "nflchannel" to schedule("Classic Game", "Game Rewind", "Top 100 Players", "Classic Game"),
    )

    @Test fun `loose names drop filler words and a trailing by phrase`() {
        assertEquals(looseName("Modern Marvels"), looseName("Modern Marvels by HISTORY"))
        assertEquals(looseName("The Hill"), looseName("The Hill TV"))
        assertEquals(looseName("TYT"), looseName("TYT Network"))
        assertEquals(looseName("NHRA"), looseName("NHRA TV"))
        assertEquals(looseName("Ghost Hunters"), looseName("Ghost Hunters Channel"))
        assertEquals("nfl", looseName("NFL Network"))
        assertEquals("nfl", looseName("NFL Channel"))
        assertEquals("standby", looseName("Stand By"), "by needs words after it")
        assertNull(looseName("The Channel"))
    }

    @Test fun `matching schedules merge into the station with more sources and are built with`() {
        val first = build(policy, catalog)
        val learning = learnAliases(first, policy, schedules)
        assertEquals(mapOf("ghosthunterschannel" to "ghosthunters"), learning.learned)
        assertEquals(
            listOf("modernmarvelsbyhistory: possible alias of modernmarvels"),
            learning.suggestions,
            "no schedule to compare is a suggestion, not a merge",
        )

        val merged = build(withLearned(policy, learning.learned), catalog).with(learning)
        assertEquals(listOf("w1-o1", "w1-o2", "s1-o1"), merged.stations.single { it.key == "ghosthunters" }.offeringIds)
        assertTrue(merged.stations.none { it.key == "ghosthunterschannel" })
        assertEquals(learning.learned, merged.learned)
        assertTrue(
            merged.stations.any {
                it.key == "nflnetwork"
            } && merged.stations.any { it.key == "nflchannel" },
            "different schedules never merge",
        )
        assertTrue(merged.suggestedAliases.none { "nfl" in it }, "a pair the schedules tell apart is not suggested")
    }

    @Test fun `the target is the station with more sources, ties by key`() {
        val tie =
            listOf(
                candidate("whale", "a", "Ghost Hunters Channel", "1"),
                candidate("samsung", "b", "Ghost Hunters", "2"),
            )
        assertEquals(
            mapOf("ghosthunterschannel" to "ghosthunters"),
            learnAliases(build(policy, tie), policy, schedules).learned,
        )
        val more =
            listOf(
                candidate("whale", "a", "Ghost Hunters Channel", "1", offerings = 3),
                candidate("samsung", "b", "Ghost Hunters", "2"),
            )
        assertEquals(
            mapOf("ghosthunters" to "ghosthunterschannel"),
            learnAliases(build(policy, more), policy, schedules).learned,
        )
    }

    @Test fun `names never keeps a pair apart and drops a learned entry for it`() {
        val never = policy.copy(names = PolicyNames(never = listOf(listOf("ghosthunterschannel", "ghosthunters"))))
        val learning = learnAliases(build(never, catalog), never, schedules)
        assertTrue(learning.learned.isEmpty())
        assertTrue(learning.suggestions.none { "ghost" in it })

        val stale = never.copy(names = never.names.copy(learned = mapOf("ghosthunterschannel" to "ghosthunters")))
        assertTrue(effectiveLearned(stale.names).isEmpty())
        assertEquals(2, build(stale, catalog).stations.count { it.key.startsWith("ghosthunters") })
        assertEquals(emptyList(), validatePolicy(stale, setOf("whale", "samsung")))
    }

    @Test fun `an override holding more than a number is never merged away`() {
        val pinned = policy.copy(
            stations = mapOf("ghosthunterschannel" to StationOverride(number = "7", group = "Crime")),
        )
        val learning = learnAliases(build(pinned, catalog), pinned, schedules)
        assertTrue(learning.learned.isEmpty())
        assertEquals(
            listOf(
                "ghosthunterschannel: schedule matches ghosthunters, but its station override holds more than a " +
                    "number; not merged",
            ),
            learning.findings,
        )
        val numbered = policy.copy(
            stations = mapOf("ghosthunterschannel" to StationOverride(number = "7")),
            numbering = PolicyNumbering(assigned = listOf("ghosthunterschannel")),
        )
        val learned = learnAliases(build(numbered, catalog), numbered, schedules).learned
        assertEquals(mapOf("ghosthunterschannel" to "ghosthunters"), learned)
        assertFalse(
            "ghosthunterschannel" in withLearned(numbered, learned).stations,
            "a number the server wrote goes with the station",
        )
    }

    @Test fun `a station whose number was set by hand is never merged away`() {
        val handSet = policy.copy(stations = mapOf("ghosthunterschannel" to StationOverride(number = "5")))
        val learning = learnAliases(build(handSet, catalog), handSet, schedules)
        assertTrue(learning.learned.isEmpty())
        assertEquals(
            listOf("ghosthunterschannel: schedule matches ghosthunters, but its number was set by hand; not merged"),
            learning.findings,
        )
        assertTrue("ghosthunterschannel: possible alias of ghosthunters" in learning.suggestions)
    }

    @Test fun `schedules match on three programmes and 60 percent of the smaller one, starting within five minutes`() {
        assertTrue(sameSchedule(hunts, schedules.getValue("ghosthunterschannel")))
        assertFalse(
            sameSchedule(hunts, schedule("Ghost Hunters", "Ghost Hunters", "Other", "Other")),
            "two matches are not enough",
        )
        assertFalse(
            sameSchedule(
                hunts,
                schedule(
                    "Ghost Hunters",
                    "Ghost Hunters",
                    "Ghost Hunters: Live!",
                    "Paranormal Lockdown",
                    offsetMinutes = 6,
                ),
            ),
        )
        val long = schedule("A", "B", "C", "D", "E", "F")
        assertFalse(sameSchedule(long, schedule("A", "B", "C", "x", "y", "z")), "three of six is under 60%")
        assertTrue(sameSchedule(long, schedule("A", "B", "C", "D", "y", "z")))
        assertFalse(sameSchedule(emptyList(), hunts))
    }

    @Test fun `learning is deterministic over input order`() {
        val expected = learnAliases(build(policy, catalog), policy, schedules)
        repeat(10) { seed ->
            val shuffled =
                learnAliases(
                    build(policy, catalog.shuffled(Random(seed))),
                    policy,
                    schedules.entries.shuffled(Random(seed)).associate {
                        it.key to
                            it.value
                    },
                )
            assertEquals(expected.learned, shuffled.learned)
            assertEquals(expected.suggestions, shuffled.suggestions)
        }
    }

    @Test fun `learned aliases resolve without chains, and aliases win`() {
        assertEquals(mapOf("a" to "c", "b" to "c"), mergeLearned(mapOf("a" to "b"), mapOf("b" to "c")))
        assertEquals(mapOf("b" to "c"), mergeLearned(mapOf("a" to "b"), mapOf("b" to "c"), drop = setOf("a")))
        val keys = StationKeys(
            aliases = mapOf("x" to "y", "z" to "w"),
            version = 2,
            learned = mapOf(
                "x" to "q",
                "y" to "v",
                "m" to "z",
            ),
        )
        assertEquals("v", keys.resolve("x"), "an alias applies first, then a learned alias of its target")
        assertEquals("w", keys.resolve("m"), "a learned target that is an alias resolves through it")
        val errors =
            validatePolicy(
                LineupPolicy(names = PolicyNames(learned = mapOf("a" to "b", "b" to "c"), never = listOf(listOf("a")))),
                emptySet(),
            )
        assertTrue(errors.any { "\"a\" -> \"b\" chains through another learned alias" in it }, "$errors")
        assertTrue(errors.any { "names.never[0]: must be two different keys" in it })
        assertTrue(
            validatePolicy(
                LineupPolicy(
                    names = PolicyNames(learned = mapOf("a" to "b")),
                    stations = mapOf("a" to StationOverride()),
                ),
                emptySet(),
            ).any {
                "key is a learned alias of \"b\"" in it
            },
        )
    }
}
