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

    @Test fun `versions may have gaps and a later binary applies only what is missing`() {
        val dir = Files.createTempDirectory("sofa-migration-gap-")
        val db = databaseAt(dir)
        try {
            applyMigrations(db, listOf(Migration(1, "CREATE TABLE one(v TEXT)"), Migration(10, "CREATE TABLE ten(v TEXT)")))
            applyMigrations(db, listOf(Migration(1, "CREATE TABLE one(v TEXT)"), Migration(4, "CREATE TABLE four(v TEXT)"), Migration(10, "CREATE TABLE ten(v TEXT)")))
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT version FROM schema_version ORDER BY version").use { rows ->
                        val versions = buildList { while (rows.next()) add(rows.getInt(1)) }
                        assertEquals(listOf(1, 4, 10), versions)
                    }
                }
            }
            assertFails("an older binary must refuse a newer schema") { applyMigrations(db, listOf(Migration(1, "CREATE TABLE one(v TEXT)"))) }
            assertFails { applyMigrations(db, listOf(Migration(10, ""), Migration(1, ""))) }
        } finally { TransactionManager.closeAndUnregister(db); dir.toFile().deleteRecursively() }
    }

    @Test fun `shipped migrations open and carry the lineup and guide tables`() {
        val dir = Files.createTempDirectory("sofa-migration-shipped-")
        val db = databaseAt(dir)
        try {
            applyMigrations(db, foundationMigrations())
            assertEquals(listOf(1, 2, 3, 4, 10), foundationMigrations().map { it.version })
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { rows ->
                        val tables = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertTrue(tables.containsAll(listOf("lineup_entry", "lineup_offering", "guide_channel", "guide_programme", "guide_import")), tables.toString())
                    }
                }
            }
        } finally { TransactionManager.closeAndUnregister(db); dir.toFile().deleteRecursively() }
    }
}
