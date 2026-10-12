package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        } finally { closeDatabase(db); dir.toFile().deleteRecursively() }
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
        } finally { closeDatabase(db); dir.toFile().deleteRecursively() }
    }

    @Test fun `a lineup written before the curation columns opens with defaults after the upgrade`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-migration-upgrade-")
        try {
            val db = databaseAt(dir)
            try {
                applyMigrations(db, foundationMigrations().filter { it.version < 40 })
                transaction(db) {
                    exec("INSERT INTO lineup_entry(id, position, number, name, group_name, logo, enabled, guide_id, guide_channel, updated_at) VALUES ('b0f6d2f4-0000-4000-8000-000000000001', 0, '5', 'First', 'News', NULL, 1, 'g', 'x', '2026-10-10T00:00:00Z')")
                    exec("INSERT INTO lineup_offering(entry_id, position, offering_id) VALUES ('b0f6d2f4-0000-4000-8000-000000000001', 0, 'o1')")
                }
            } finally { closeDatabase(db) }
            openStore(dir).use { store ->
                val entry = store.lineup().single()
                assertEquals(LineupEntryRecord("b0f6d2f4-0000-4000-8000-000000000001", "5", "First", "News", null, true, GuideMapping("g", "x"), listOf("o1")), entry)
                assertTrue(entry.pinned.isEmpty()); assertEquals("manual", entry.createdBy); assertNull(entry.autoDisabledAt); assertNull(entry.autoDisabledReason)
                assertTrue(store.curationRules().isEmpty()); assertNull(store.curationSettings()); assertTrue(store.unavailableSince().isEmpty())
                assertTrue(store.stationAvailability().isEmpty())
                val remembered = mapOf(
                    "news" to dev.jamesrich.sofaking.backend.catalog.StationAvailability(
                        java.time.Instant.parse("2026-10-10T00:00:00Z"), true, lastNumber = "5", lastName = "News", pinOfferings = mapOf("p/tvg-id:n" to listOf("o1")),
                    ),
                    "gone" to dev.jamesrich.sofaking.backend.catalog.StationAvailability(missingSince = java.time.Instant.parse("2026-10-09T00:00:00Z")),
                )
                store.saveStationAvailability(remembered, java.time.Instant.parse("2026-10-10T01:00:00Z"))
                assertEquals(remembered, store.stationAvailability())
                store.saveStationAvailability(mapOf("news" to remembered.getValue("news")), java.time.Instant.parse("2026-10-10T02:00:00Z"))
                assertEquals(setOf("news"), store.stationAvailability().keys, "a save replaces every row")
            }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT version FROM schema_version ORDER BY version").use { rows ->
                        assertEquals(listOf(1, 2, 3, 4, 10, 30, 40, 41, 50, 60), buildList { while (rows.next()) add(rows.getInt(1)) })
                    }
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `shipped migrations open and carry the lineup and guide tables`() {
        val dir = Files.createTempDirectory("sofa-migration-shipped-")
        val db = databaseAt(dir)
        try {
            applyMigrations(db, foundationMigrations())
            assertEquals(listOf(1, 2, 3, 4, 10, 30, 40, 41, 50, 60), foundationMigrations().map { it.version })
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { rows ->
                        val tables = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertTrue(tables.containsAll(listOf("lineup_entry", "lineup_offering", "guide_channel", "guide_programme", "guide_import", "logo_cache", "station_availability")), tables.toString())
                    }
                }
            }
        } finally { closeDatabase(db); dir.toFile().deleteRecursively() }
    }
}
