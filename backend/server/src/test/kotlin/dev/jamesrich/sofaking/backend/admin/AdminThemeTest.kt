package dev.jamesrich.sofaking.backend.admin

import java.time.Instant
import kotlin.test.*

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
}
