package dev.jamesrich.sofaking.backend.playback

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStatsTest {
    private val start = Instant.parse("2026-10-25T00:00:00Z")

    @Test fun `the bitrate covers the last reading at least 10 seconds old and holds between readings`() {
        val stats = SessionStats(start)
        assertEquals(0, stats.bitrateKbps(start), "nothing sent yet")
        stats.bytes.addAndGet(500_000)
        assertEquals(1_000, stats.bitrateKbps(start.plusSeconds(4)), "the first reading counts from the start")
        stats.bytes.addAndGet(1_000_000)
        assertEquals(1_000, stats.bitrateKbps(start.plusSeconds(6)), "a reading inside the window holds")
        assertEquals(1_200, stats.bitrateKbps(start.plusSeconds(10)), "1.5 MB over 10 s")
        stats.bytes.addAndGet(250_000)
        assertEquals(1_200, stats.bitrateKbps(start.plusSeconds(15)))
        assertEquals(200, stats.bitrateKbps(start.plusSeconds(20)), "250 kB over the next 10 s")
    }
}
