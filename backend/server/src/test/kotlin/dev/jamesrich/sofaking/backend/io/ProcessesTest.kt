package dev.jamesrich.sofaking.backend.io

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessesTest {
    @Test fun `waitForOrKill returns true for a process that exits in time`() {
        val process = ProcessBuilder("true").start()
        assertTrue(process.waitForOrKill(5, TimeUnit.SECONDS))
        assertFalse(process.isAlive)
    }

    @Test fun `waitForOrKill ends and reaps a process that outlives the timeout`() {
        val process = ProcessBuilder("sleep", "30").start()
        assertFalse(process.waitForOrKill(200, TimeUnit.MILLISECONDS))
        assertFalse(process.isAlive)
    }
}
