package dev.jamesrich.sofaking.backend.dvr

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ReopenDelayTest {
    private val slowest = Duration.ofSeconds(5)

    @Test fun `a read that wrote media or stalled reopens after a second`() {
        val delay = ReopenDelay(slowest)
        assertEquals(Duration.ofSeconds(1), delay.after(null))
        assertEquals(Duration.ofSeconds(1), delay.after("SOURCE_STALLED"))
    }

    @Test fun `reads with no media in a row back off to the slowest and media starts over`() {
        val delay = ReopenDelay(slowest)
        val waits = List(5) { delay.after("NO_MEDIA").seconds }
        assertEquals(listOf(1L, 2L, 4L, 5L, 5L), waits)
        assertEquals(Duration.ofSeconds(1), delay.after(null))
        assertEquals(Duration.ofSeconds(1), delay.after("NO_MEDIA"))
    }

    @Test fun `any other failure waits the slowest and ends a run of empty reads`() {
        val delay = ReopenDelay(slowest)
        delay.after("NO_MEDIA")
        assertEquals(slowest, delay.after("SOURCE_UNAVAILABLE"))
        assertEquals(Duration.ofSeconds(1), delay.after("NO_MEDIA"))
    }

    @Test fun `a slowest wait under a second is also the first`() {
        val delay = ReopenDelay(Duration.ofMillis(200))
        assertEquals(Duration.ofMillis(200), delay.after(null))
        assertEquals(Duration.ofMillis(200), delay.after("NO_MEDIA"))
        assertEquals(Duration.ofMillis(200), delay.after("NO_MEDIA"))
    }
}
