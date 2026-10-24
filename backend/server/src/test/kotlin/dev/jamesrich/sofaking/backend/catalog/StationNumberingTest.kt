package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyGroups
import dev.jamesrich.sofaking.backend.api.PolicyHealth
import dev.jamesrich.sofaking.backend.api.PolicyNames
import dev.jamesrich.sofaking.backend.api.PolicyNumbering
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.api.StationOverride
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How stations are numbered: overrides, provider numbers, group blocks and the floor. */
class StationNumberingTest : StationBuilderCase() {
    @Test fun `numbers come from overrides first, then provider numbers in priority order, then the floor`() {
        val numbered = LineupPolicy(
            names = PolicyNames(version = 1),
            providers = listOf(PolicyProvider("a"), PolicyProvider("b")),
            numbering = PolicyNumbering(floor = 1000),
            stations = mapOf(
                "reserved" to StationOverride(number = "5"),
                "pinned high" to StationOverride(number = "1000"),
            ),
        )
        val result = build(
            numbered,
            listOf(
                candidate("a", "a1", "Reserved", "77"),
                candidate("a", "a2", "Wants Five", "5"),
                candidate("a", "a3", "Seven", "7"),
                candidate("b", "b1", "Also Seven", "0007"),
                candidate("b", "b2", "Pinned High", "3"),
                candidate("b", "b3", "No Number", "abc"),
            ),
        )
        assertEquals("5", result.station("reserved").number)
        assertEquals("1000", result.station("pinned high").number)
        assertEquals(
            mapOf("wants five" to "1001", "seven" to "7", "also seven" to "1002", "no number" to "1003"),
            result.assigned,
        )
        assertEquals(
            listOf("reserved", "seven", "pinned high", "wants five", "also seven", "no number"),
            result.stations.map {
                it.key
            },
        )

        val kept =
            build(
                numbered.copy(numbering = PolicyNumbering(floor = 10, keepProviderNumbers = false)),
                listOf(candidate("a", "a3", "Seven", "7")),
            )
        assertEquals("10", kept.station("seven").number)
    }

    @Test fun `with numbering groups a station numbers in its group's block, then from the floor`() {
        val grouped = LineupPolicy(
            names = PolicyNames(version = 1),
            providers = listOf(PolicyProvider("a")),
            groups = PolicyGroups(map = mapOf("news" to "News", "sports" to "Sports"), default = "Entertainment"),
            numbering = PolicyNumbering(
                floor = 1000,
                keepProviderNumbers = true,
                groups = mapOf(
                    "News" to 100,
                    "Sports" to 200,
                ),
                groupSize = 2,
            ),
            stations = mapOf(
                "sport b" to StationOverride(number = "200"),
                "news four" to StationOverride(number = "55"),
            ),
        )
        val result = build(
            grouped,
            listOf(
                candidate("a", "n1", "News One", "5", group = "News"),
                candidate("a", "n2", "News Two", "6", group = "News"),
                candidate("a", "n3", "News Three", "7", group = "News"),
                candidate("a", "n4", "News Four", "4", group = "News"),
                candidate("a", "s1", "Sport A", "8", group = "Sports"),
                candidate("a", "s2", "Sport B", "10", group = "Sports"),
                candidate("a", "e1", "Show", "9", group = "Fun"),
            ),
        )
        assertEquals(
            mapOf(
                "news one" to "100",
                "news two" to "101",
                "news three" to "1000",
                "sport a" to "201",
                "show" to "1001",
            ),
            result.assigned,
        )
        assertEquals("55", result.station("news four").number, "a number in the file never moves")
        assertEquals("200", result.station("sport b").number)
        assertEquals("News", result.station("news three").group)
        assertTrue(
            validatePolicy(
                grouped.copy(numbering = PolicyNumbering(groups = mapOf("" to 0), groupSize = 0)),
                setOf("a"),
            ).let { errors ->
                errors.any { "numbering.groupSize" in it } && errors.any { "start must be between" in it } &&
                    errors.any { "must be 1 to 128 characters" in it }
            },
        )
    }

    @Test fun `a group block ends where the next group's block starts`() {
        val ranged = policy.copy(
            stations = emptyMap(),
            groups = PolicyGroups(default = "Local"),
            numbering = PolicyNumbering(floor = 2000, groups = mapOf("Local" to 1, "News" to 3), groupSize = 100),
        )
        val three =
            listOf(
                candidate("whale", "a", "Alpha", "1"),
                candidate("whale", "b", "Bravo", "2"),
                candidate("whale", "c", "Charlie", "3"),
            )
        assertEquals(
            listOf("1", "2", "2000"),
            build(ranged, three).stations.map {
                it.number
            }.sorted(),
            "Local takes 1 and 2; 3 is News's, so the third falls to the floor",
        )
    }

    @Test fun `an unverified station takes a floor number that is not assigned, then its block number once verified`() {
        val blocked = LineupPolicy(
            names = PolicyNames(version = 1),
            providers = listOf(PolicyProvider("a")),
            groups = PolicyGroups(default = "Local"),
            numbering = PolicyNumbering(floor = 1000, groups = mapOf("Local" to 1), groupSize = 10),
        )
        val alpha = listOf(candidate("a", "a1", "Alpha", "5"))
        val waiting = build(blocked, alpha, health = mapOf("a1-o1" to HealthState(HealthStatus.UNVERIFIED)))
        assertEquals("unverified", waiting.station("alpha").disabledReason)
        assertEquals("1000", waiting.station("alpha").number)
        assertEquals(emptyMap(), waiting.assigned)

        val verified = build(blocked, alpha, health = mapOf("a1-o1" to HealthState(HealthStatus.HEALTHY)))
        assertEquals("1", verified.station("alpha").number)
        assertEquals(mapOf("alpha" to "1"), verified.assigned)
    }

    @Test fun `followGroup moves server-numbered stations into their group's block while it has room`() {
        val grouped = LineupPolicy(
            names = PolicyNames(version = 1),
            health = PolicyHealth(requireVerified = false),
            providers = listOf(PolicyProvider("a")),
            groups = PolicyGroups(map = mapOf("news" to "News", "sports" to "Sports")),
            numbering = PolicyNumbering(
                floor = 1000,
                groups = mapOf("News" to 100, "Sports" to 200),
                groupSize = 2,
                followGroup = true,
                assigned = listOf("news one", "news two", "news three", "sport a"),
            ),
            stations = mapOf(
                "news one" to StationOverride(number = "200"),
                "news two" to StationOverride(number = "201"),
                "news three" to StationOverride(number = "1000"),
                "sport a" to StationOverride(number = "1001"),
                "news hand" to StationOverride(number = "203"),
            ),
        )
        val catalog = listOf(
            candidate("a", "n1", "News One", "1", group = "News"),
            candidate("a", "n2", "News Two", "2", group = "News"),
            candidate("a", "n3", "News Three", "3", group = "News"),
            candidate("a", "n4", "News Hand", "4", group = "News"),
            candidate("a", "s1", "Sport A", "5", group = "Sports"),
        )
        val result = build(grouped, catalog)
        assertEquals(mapOf("news one" to "100", "news two" to "101"), result.renumbered)
        assertEquals("1000", result.station("news three").number, "the block is full, so it keeps its number")
        assertEquals("203", result.station("news hand").number, "a number set by hand never moves")
        assertEquals("1001", result.station("sport a").number, "numbers freed this run stay reserved until the next")
        assertEquals(
            emptyMap(),
            build(grouped.copy(numbering = grouped.numbering.copy(followGroup = false)), catalog).renumbered,
        )
    }
}
