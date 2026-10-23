package dev.jamesrich.sofaking.backend.admin

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminThemeTest {
    private val now: Instant = Instant.parse("2026-10-15T12:00:00Z")

    @Test fun `relative times round down to the largest whole unit in either direction`() {
        assertEquals("just now", relative(now, now))
        assertEquals("just now", relative(now.minusSeconds(59), now))
        assertEquals("1 min ago", relative(now.minusSeconds(60), now))
        assertEquals("59 min ago", relative(now.minusSeconds(3599), now))
        assertEquals("1 h ago", relative(now.minusSeconds(3600), now))
        assertEquals("23 h ago", relative(now.minusSeconds(86_399), now))
        assertEquals("1 d ago", relative(now.minusSeconds(86_400), now))
        assertEquals("30 d ago", relative(now.minusSeconds(30 * 86_400), now))
        assertEquals("in under a minute", relative(now.plusSeconds(30), now))
        assertEquals("in 5 min", relative(now.plusSeconds(300), now))
        assertEquals("in 2 h", relative(now.plusSeconds(7_200), now))
        assertEquals("in 3 d", relative(now.plusSeconds(3 * 86_400), now))
    }

    @Test fun `uptime shows days, hours or minutes and never goes negative`() {
        assertEquals("0 min", uptime(now, now))
        assertEquals("0 min", uptime(now.plusSeconds(90), now), "a start in the future reads as zero")
        assertEquals("7 min", uptime(now.minusSeconds(7 * 60 + 59), now))
        assertEquals("5 h 12 min", uptime(now.minusSeconds(5 * 3600 + 12 * 60), now))
        assertEquals("3 d 4 h", uptime(now.minusSeconds(3 * 86_400 + 4 * 3600 + 59 * 60), now))
    }

    @Test fun `thousands group with commas in every locale and plural follows the count`() {
        assertEquals("0", thousands(0))
        assertEquals("999", thousands(999))
        assertEquals("1,000", thousands(1_000))
        assertEquals("1,234,567", thousands(1_234_567))
        assertEquals("-1,234", thousands(-1_234))
        assertEquals("1 channel", plural(1, "channel"))
        assertEquals("0 channels", plural(0, "channel"))
        assertEquals("2,500 channels", plural(2_500, "channel"))
        assertEquals("3 entries", plural(3, "entry", "entries"))
    }

    @Test fun `a failing import reads by how long it has failed, warn under a day and bad after`() {
        assertNull(failingFor(now.minusSeconds(3600).toString(), now))
        assertEquals("has failed for 1 day", failingFor(now.minusSeconds(86_400).toString(), now))
        assertEquals("has failed for 7 days", failingFor(now.minusSeconds(7 * 86_400 + 60).toString(), now))
        assertEquals("has never imported", failingFor(null, now))
        assertEquals(Tone.WARN, failingTone(listOf(now.minusSeconds(3600).toString()), now))
        assertEquals(
            Tone.BAD,
            failingTone(listOf(now.minusSeconds(3600).toString(), now.minusSeconds(2 * 86_400).toString()), now),
        )
        assertEquals(Tone.BAD, failingTone(listOf(null), now))
    }

    @Test fun `periods and durations read in whole units`() {
        assertEquals("15 min", period(java.time.Duration.ofMinutes(15)))
        assertEquals("6 h", period(java.time.Duration.ofHours(6)))
        assertEquals("1 d", period(java.time.Duration.ofDays(1)))
        assertEquals("90 min", period(java.time.Duration.ofMinutes(90)))
        assertEquals("45 s", period(java.time.Duration.ofSeconds(45)))
        assertEquals("250 ms", took(250))
        assertEquals("1.5 s", took(1500))
        assertEquals("2 min 5 s", took(125_000))
    }

    @Test fun `the lineup filter keeps every parameter in its query`() {
        val filter = LineupFilter.from(
            io.ktor.http.parametersOf(
                "q" to listOf("a"),
                "override" to listOf("true"),
                "changed" to listOf("true"),
                "sort" to listOf("name"),
                "dir" to listOf("desc"),
                "page" to listOf("2"),
            ),
        )
        assertEquals("q=a&override=true&changed=true&sort=name&dir=desc&page=2", filter.query())
        assertEquals(
            LineupFilter(),
            LineupFilter.from(
                io.ktor.http.parametersOf(
                    "sort" to listOf("bogus"),
                    "dir" to listOf("sideways"),
                ),
            ),
            "unknown sorts and directions are dropped",
        )
    }
}
