package dev.jamesrich.sofaking.backend.jobs

import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredJobAlertsTest {
    @Test fun `the jobs that alerted survive reopening the store`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-job-alerts-")
        try {
            openStore(dir).use { store ->
                val alerts = StoredJobAlerts(store)
                assertEquals(emptySet(), alerts.alerting())
                alerts.remember("guide:a", true)
                alerts.remember("import:b", true)
                alerts.remember("guide:a", false)
            }
            openStore(dir).use { store -> assertEquals(setOf("import:b"), StoredJobAlerts(store).alerting()) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
