package dev.jamesrich.sofaking.backend.store

import java.nio.file.Files
import java.sql.DriverManager
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.*

class MigrationsTest {
    @Test fun `failed migration rolls back schema and version atomically`() {
        val dir = Files.createTempDirectory("sofa-migration-test-")
        val db = databaseAt(dir)
        try {
            assertFails { applyMigrations(db, listOf(Migration(1, "CREATE TABLE partial(value TEXT); INVALID SQL"))) }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('partial','schema_version')").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0, rows.getInt(1))
                    }
                }
            }
        } finally { TransactionManager.closeAndUnregister(db); dir.toFile().deleteRecursively() }
    }
}
