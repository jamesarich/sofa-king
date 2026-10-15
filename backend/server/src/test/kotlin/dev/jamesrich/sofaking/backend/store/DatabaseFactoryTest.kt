package dev.jamesrich.sofaking.backend.store

import java.nio.file.Files
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.sqlite.SQLiteConnection
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
        } finally { closeDatabase(db); dir.toFile().deleteRecursively() }
    }

    @Test fun `transactions share one connection, so the WAL stays open until the database closes`() {
        val dir = Files.createTempDirectory("sofa-pinned-")
        val db = databaseAt(dir)
        var open = true
        try {
            val first = transaction(db) { exec("CREATE TABLE t(v TEXT)"); (connection.connection as java.sql.Connection).unwrap(SQLiteConnection::class.java) }
            val second = transaction(db) { exec("INSERT INTO t VALUES ('a')"); (connection.connection as java.sql.Connection).unwrap(SQLiteConnection::class.java) }
            assertSame(first, second, "both transactions ran on the same physical connection")
            assertFalse(first.isClosed, "returning a handle leaves the physical connection open")
            assertTrue(Files.exists(dir.resolve("backend.sqlite-wal")), "no checkpoint-and-delete after each transaction")
            assertEquals(1, transaction(db) { exec("SELECT COUNT(*) FROM t") { it.next(); it.getInt(1) } })
            closeDatabase(db)
            open = false
            assertFalse(Files.exists(dir.resolve("backend.sqlite-wal")), "closing the last connection checkpoints the WAL")
        } finally { if (open) closeDatabase(db); dir.toFile().deleteRecursively() }
    }
}
