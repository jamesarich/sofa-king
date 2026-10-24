package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyHealth
import dev.jamesrich.sofaking.backend.api.PolicyNames
import dev.jamesrich.sofaking.backend.api.PolicyNumbering
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.api.StationOverride
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.contract.Channel
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How availability and verification turn stations off and on. */
class StationAvailabilityTest : StationBuilderCase() {
    @Test fun `availability turns a station off after disableAfterHours and back on when a source is healthy`() {
        val one = listOf(candidate("whale", "w1", "Bob Ross Channel", "15"))
        val broken = mapOf("w1-o1" to HealthState(HealthStatus.QUARANTINED))
        val fresh = build(policy, one, health = broken)
        assertTrue(fresh.station("bob ross channel").enabled)
        assertEquals(
            StationAvailability(now, false, lastNumber = "15", lastName = "Bob Ross Channel"),
            fresh.availability["bob ross channel"],
        )

        val since = now.minus(Duration.ofHours(25))
        val off =
            build(policy, one, health = broken, previous = mapOf("bob ross channel" to StationAvailability(since)))
        assertEquals("unavailable", off.station("bob ross channel").disabledReason)
        assertEquals(since, off.availability.getValue("bob ross channel").unavailableSince)

        val eligibleOnly = mapOf("w1-o1" to HealthState(HealthStatus.UNVERIFIED))
        val stillOff =
            build(
                policy,
                one,
                health = eligibleOnly,
                previous = mapOf("bob ross channel" to StationAvailability(null, autoDisabled = true)),
            )
        assertEquals(
            "unavailable",
            stillOff.station("bob ross channel").disabledReason,
            "enableOn healthy waits for a healthy source",
        )
        val eligibleOn =
            build(
                policy.copy(health = PolicyHealth(enableOn = "eligible", requireVerified = false)),
                one,
                health = eligibleOnly,
                previous = mapOf("bob ross channel" to StationAvailability(null, autoDisabled = true)),
            )
        assertTrue(eligibleOn.station("bob ross channel").enabled)
        val back =
            build(policy, one, previous = mapOf("bob ross channel" to StationAvailability(null, autoDisabled = true)))
        assertTrue(back.station("bob ross channel").enabled)
    }

    @Test fun `stations whose candidates vanish stay disabled for keepMissingDays`() {
        val previous = mapOf(
            "recent" to
                StationAvailability(
                    lastNumber = "44",
                    lastName = "Recent",
                    missingSince = now.minus(Duration.ofDays(2)),
                ),
            "old" to
                StationAvailability(lastNumber = "45", lastName = "Old", missingSince = now.minus(Duration.ofDays(8))),
            "just gone" to StationAvailability(lastNumber = "46", lastName = "Just Gone"),
        )
        val result = build(policy, catalog, previous = previous)
        val recent = result.station("recent")
        assertFalse(recent.enabled)
        assertEquals("missing", recent.disabledReason)
        assertEquals("44", recent.number)
        assertEquals("Recent", recent.name)
        assertTrue(result.stations.none { it.key == "old" })
        assertEquals(now, result.availability.getValue("just gone").missingSince)
        assertFalse("old" in result.availability)
    }

    private val verifying = policy.copy(health = PolicyHealth(requireVerified = true))
    private val two = listOf(candidate("whale", "w1", "Bob Ross Channel", "15", offerings = 2))
    private val unverified = mapOf(
        "w1-o1" to HealthState(HealthStatus.UNVERIFIED),
        "w1-o2" to HealthState(HealthStatus.SUSPECT),
    )

    @Test fun `with requireVerified a station stays off until a source has qualified healthy once`() {
        val waiting = build(verifying, two, health = unverified)
        assertEquals("unverified", waiting.station("bob ross channel").disabledReason)
        assertNull(waiting.availability.getValue("bob ross channel").verifiedAt)
        assertEquals(
            mapOf("w1-o1" to 0, "w1-o2" to 1),
            probeTiers(waiting),
            "the first source of an unverified station is probed first",
        )
        val required = build(
            verifying.copy(stations = verifying.stations + ("bob ross channel" to StationOverride(required = true))),
            two,
            health = unverified,
        )
        assertEquals(
            "unverified",
            required.station("bob ross channel").disabledReason,
            "a required station waits too, in the lineup",
        )
        val qualified = build(verifying, two, health = unverified + ("w1-o2" to HealthState(HealthStatus.HEALTHY)))
        assertTrue(qualified.station("bob ross channel").enabled)
        assertEquals(now, qualified.availability.getValue("bob ross channel").verifiedAt)
        assertEquals(mapOf("w1-o1" to 2, "w1-o2" to 2), probeTiers(qualified))
        val once = build(
            verifying,
            two,
            health = mapOf("w1-o1" to HealthState(HealthStatus.SUSPECT, lastQualifiedAt = now.minusSeconds(60))),
        )
        assertTrue(once.station("bob ross channel").enabled, "a source that qualified before counts")
    }

    @Test fun `once verified a station follows the availability rules while no source is healthy`() {
        val verifiedAt = now.minus(Duration.ofDays(3))
        val later = build(
            verifying,
            two,
            health = mapOf(
                "w1-o1" to HealthState(HealthStatus.QUARANTINED),
                "w1-o2" to HealthState(HealthStatus.QUARANTINED),
            ),
            previous = mapOf("bob ross channel" to StationAvailability(verifiedAt = verifiedAt)),
        )
        assertTrue(later.station("bob ross channel").enabled)
        assertEquals(verifiedAt, later.availability.getValue("bob ross channel").verifiedAt)
        val stale = mapOf(
            "bob ross channel" to
                StationAvailability(unavailableSince = now.minus(Duration.ofHours(25)), verifiedAt = verifiedAt),
        )
        assertEquals(
            "unavailable",
            build(
                verifying,
                two,
                health = mapOf("w1-o1" to HealthState(HealthStatus.QUARANTINED)),
                previous = stale,
            ).station("bob ross channel").disabledReason,
        )
    }

    @Test fun `a source the replaced lineup carried counts as verified`() {
        val seeded = build(verifying, two, health = unverified, seed = setOf("w1-o2"))
        assertTrue(seeded.station("bob ross channel").enabled)
        assertEquals(now, seeded.availability.getValue("bob ross channel").verifiedAt)
    }

    @Test fun `a station the policy turns off is not probed first`() {
        val off = build(
            verifying.copy(stations = mapOf("bob ross channel" to StationOverride(enabled = false))),
            two,
            health = unverified,
        )
        assertEquals(mapOf("w1-o1" to 2, "w1-o2" to 2), probeTiers(off))
    }

    @Test fun `a station gone past keepMissingDays leaves, and the number it held is free for the next station`() {
        val numbered = LineupPolicy(
            names = PolicyNames(version = 1),
            health = PolicyHealth(requireVerified = false, keepMissingDays = 7),
            providers = listOf(PolicyProvider("a")),
            numbering = PolicyNumbering(floor = 1000),
            stations = mapOf("gone" to StationOverride(number = "5")),
        )
        val previous = mapOf(
            "gone" to
                StationAvailability(lastNumber = "5", lastName = "Gone", missingSince = now.minus(Duration.ofDays(8))),
        )
        val leaving = build(numbered, listOf(candidate("a", "a1", "Five", "5")), previous = previous)
        assertEquals(setOf("gone"), leaving.reclaimed)
        assertEquals("1000", leaving.station("five").number, "the file still holds 5 during the run that reclaims it")
        assertEquals(mapOf("five" to "1000"), leaving.assigned)

        val next = build(numbered.copy(stations = emptyMap()), listOf(candidate("a", "a1", "Five", "5")))
        assertEquals("5", next.station("five").number, "once the file no longer holds 5, the next station takes it")
        assertEquals(mapOf("five" to "5"), next.assigned)
    }
}
