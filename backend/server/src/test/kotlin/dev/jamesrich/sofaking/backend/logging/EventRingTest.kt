package dev.jamesrich.sofaking.backend.logging

import ch.qos.logback.classic.LoggerContext
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventRingTest {
    @Test fun `the ring keeps the newest lines at INFO and above, newest first, with the request id`() {
        val ring = EventRing(capacity = 3)
        val context = LoggerContext().apply { mdcAdapter = MDC.getMDCAdapter() }
        try {
            configureLogging(context, LogSettings("debug", "text"), java.io.ByteArrayOutputStream(), ring)
            val log = context.getLogger("dev.jamesrich.sofaking.backend.Test")
            log.debug("quiet")
            log.info("one")
            MDC.put("requestId", "abc123")
            try { log.warn("two {}", "args") } finally { MDC.remove("requestId") }
            log.error("three", IllegalStateException("message that stays out"))
            log.info("four")
            val events = ring.snapshot()
            assertEquals(listOf("four", "three (IllegalStateException)", "two args"), events.map { it.message })
            assertEquals(listOf("INFO", "ERROR", "WARN"), events.map { it.level })
            assertEquals("abc123", events[2].requestId)
            assertNull(events[0].requestId)
            assertEquals("dev.jamesrich.sofaking.backend.Test", events[0].logger)
        } finally { context.stop() }
    }
}
