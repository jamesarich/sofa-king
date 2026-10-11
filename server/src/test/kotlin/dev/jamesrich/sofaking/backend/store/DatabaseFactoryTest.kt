package dev.jamesrich.sofaking.backend.store

import java.nio.file.Files
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*

class DatabaseFactoryTest {
    @Test fun `connections run in WAL with NORMAL synchronous`() {
        val dir = Files.createTempDirectory("sofa-pragmas-")
        val db = databaseAt(dir)
        try {
            val (synchronous, journal) = transaction(db) {
                exec("PRAGMA synchronous") { it.next(); it.getInt(1) } to exec("PRAGMA journal_mode") { it.next(); it.getString(1) }
            }
            assertEquals(1, synchronous)
            assertEquals("wal", journal)
        } finally { TransactionManager.closeAndUnregister(db); dir.toFile().deleteRecursively() }
    }
}
