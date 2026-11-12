package dev.jamesrich.sofaking.backend.jobs

import dev.jamesrich.sofaking.backend.store.openStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredJobRunsTest {
    @Test fun `each job's newest run survives reopening the store`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-job-runs-")
        try {
            val scan = LastRun(1_000, 4_000, true, "12 files")
            val backup = LastRun(5_000, 6_000, false, "IOException")
            openStore(dir).use { store ->
                val runs = StoredJobRuns(store)
                assertEquals(emptyMap(), runs.all())
                runs.remember("library-scan", LastRun(0, 500, false))
                runs.remember("backup", backup)
                runs.remember("library-scan", scan)
            }
            openStore(dir).use { store ->
                assertEquals(mapOf("library-scan" to scan, "backup" to backup), StoredJobRuns(store).all())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
