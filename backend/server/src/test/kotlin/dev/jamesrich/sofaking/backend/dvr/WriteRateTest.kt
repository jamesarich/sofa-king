package dev.jamesrich.sofaking.backend.dvr

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteRateTest {
    private val start = Instant.parse("2026-10-31T16:49:30Z")

    /** Feeds [rate] a sample a second for [seconds] at [perSecond] bytes, from [written]: the last answer and total. */
    private fun run(rate: WriteRate, from: Int, seconds: Int, perSecond: Long, written: Long): Pair<Boolean, Long> {
        var total = written
        var slowed = false
        for (s in from until from + seconds) {
            total += perSecond
            slowed = rate.slowed(total, start.plusSeconds(s.toLong()))
        }
        return slowed to total
    }

    @Test fun `a read at a steady rate is never slowed`() {
        val rate = WriteRate()
        val (slowed, _) = run(rate, 0, 300, 326_000, 0)
        assertFalse(slowed)
    }

    @Test fun `a read that falls to a trickle after running full is slowed within a window`() {
        val rate = WriteRate()
        val (full, written) = run(rate, 0, 120, 326_000, 0)
        assertFalse(full)
        val (slowed, _) = run(rate, 120, 31, 30_000, written)
        assertTrue(slowed)
    }

    @Test fun `an opening burst sets no peak and a later full rate is not slowed against it`() {
        val rate = WriteRate()
        val (_, burst) = run(rate, 0, 20, 1_500_000, 0)
        val (slowed, _) = run(rate, 20, 200, 326_000, burst)
        assertFalse(slowed)
    }

    @Test fun `a drop to a third is the line`() {
        val rate = WriteRate(window = Duration.ofSeconds(10), settle = Duration.ofSeconds(20))
        val (_, written) = run(rate, 0, 60, 300, 0)
        val (held, more) = run(rate, 60, 11, 101, written)
        assertFalse(held, "a window at over a third of the best is not slowed")
        val (slowed, _) = run(rate, 71, 11, 99, more)
        assertTrue(slowed, "a window under a third of the best is")
    }
}
