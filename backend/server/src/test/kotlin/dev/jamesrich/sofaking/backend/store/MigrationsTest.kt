package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.GuideProgramme
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.PreviouslyShown
import dev.jamesrich.sofaking.backend.catalog.ProgrammeRating
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

    @Test fun `a lineup written before the created-by and auto-disable columns opens with defaults after the upgrade`() = runBlocking<Unit> {
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
                assertEquals("manual", entry.createdBy); assertNull(entry.autoDisabledAt); assertNull(entry.autoDisabledReason)
                assertTrue(store.policyChanges(10).isEmpty())
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
                        assertEquals(listOf(1, 2, 3, 4, 10, 30, 40, 41, 50, 60, 70, 80, 90), buildList { while (rows.next()) add(rows.getInt(1)) })
                    }
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `the upgrade drops the curation tables and keeps the lineup and the policy changes`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-migration-curation-")
        try {
            val db = databaseAt(dir)
            try {
                applyMigrations(db, foundationMigrations().filter { it.version < 70 })
                transaction(db) {
                    exec("INSERT INTO lineup_entry(id, position, number, name, group_name, logo, enabled, guide_id, guide_channel, updated_at, pinned, created_by, auto_disabled_at, auto_disabled_reason) VALUES ('b0f6d2f4-0000-4000-8000-000000000001', 0, '5', 'First', 'News', NULL, 0, NULL, NULL, '2026-10-10T00:00:00Z', 'group,name', 'policy', '2026-10-09T00:00:00Z', 'unavailable')")
                    exec("INSERT INTO lineup_offering(entry_id, position, offering_id) VALUES ('b0f6d2f4-0000-4000-8000-000000000001', 0, 'o1')")
                    exec("INSERT INTO curation_rule(id, kind, position, rule_json) VALUES ('r', 'group', 0, '{}')")
                    exec("INSERT INTO curation_entry_state(entry_id, unavailable_since) VALUES ('b0f6d2f4-0000-4000-8000-000000000001', '2026-10-09T00:00:00Z')")
                    exec("INSERT INTO curation_adopted(channel_id, entry_id, adopted_at) VALUES ('c', 'b0f6d2f4-0000-4000-8000-000000000001', '2026-10-09T00:00:00Z')")
                    exec("INSERT INTO metadata(key, value) VALUES ('curation:settings', '{}')")
                    exec("""INSERT INTO curation_change(applied_at, entry_id, action_json) VALUES ('2026-10-09T00:00:00Z', NULL, '{"kind":"regroup","entryName":"Old","reason":"rule"}')""")
                    exec("""INSERT INTO curation_change(applied_at, entry_id, action_json) VALUES ('2026-10-10T00:00:00Z', 'b0f6d2f4-0000-4000-8000-000000000001', '{"kind":"policy","entryId":"b0f6d2f4-0000-4000-8000-000000000001","entryName":"First","field":"enabled","before":"true","after":"false","reason":"health: unavailable","ruleId":null,"offeringIds":[],"key":"first"}')""")
                }
            } finally { closeDatabase(db) }
            openStore(dir).use { store ->
                val entry = store.lineup().single()
                assertEquals(
                    LineupEntryRecord(
                        "b0f6d2f4-0000-4000-8000-000000000001", "5", "First", "News", enabled = false, offeringIds = listOf("o1"), createdBy = "policy",
                        autoDisabledAt = java.time.Instant.parse("2026-10-09T00:00:00Z"), autoDisabledReason = "unavailable",
                    ),
                    entry,
                )
                val change = store.policyChanges(10).single()
                assertEquals("2026-10-10T00:00:00Z", change.appliedAt)
                assertEquals(dev.jamesrich.sofaking.backend.api.PolicyChange("b0f6d2f4-0000-4000-8000-000000000001", "First", "enabled", "true", "false", "health: unavailable", "first"), change.change)
            }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE 'curation%'").use { rows -> assertFalse(rows.next(), "no curation table or index is left") }
                    stmt.executeQuery("SELECT COUNT(*) FROM metadata WHERE key = 'curation:settings'").use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                    stmt.executeQuery("SELECT COUNT(*) FROM pragma_table_info('lineup_entry') WHERE name = 'pinned'").use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `programmes stored before the details column read with no metadata, and new ones keep theirs`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-migration-details-")
        try {
            val db = databaseAt(dir)
            try {
                applyMigrations(db, foundationMigrations().filter { it.version < 80 })
                transaction(db) {
                    exec("INSERT INTO guide_import(guide_id, imported_at) VALUES ('g', '2026-10-10T00:00:00Z')")
                    exec("INSERT INTO guide_programme(guide_id, channel_id, starts_at, ends_at, title, description) VALUES ('g', 'c', 1791640800, 1791644400, 'Old', 'Kept')")
                }
            } finally { closeDatabase(db) }
            val start = java.time.Instant.ofEpochSecond(1791640800)
            val end = java.time.Instant.ofEpochSecond(1791644400)
            openStore(dir).use { store ->
                assertEquals(GuideProgramme("c", start, end, "Old", "Kept"), store.programmes("g", listOf("c"), start, end).getValue("c").single())
                val rich = GuideProgramme(
                    "c", start, end, "New", subTitle = "Part One", episodeNumbers = listOf(EpisodeNumber("xmltv_ns", "0.1.")),
                    icon = "https://img.test/p.jpg", categories = listOf("News"), rating = ProgrammeRating("TV-G", "VCHIP"), isNew = true,
                    previouslyShown = PreviouslyShown(), date = "2026", language = "en",
                )
                val plain = GuideProgramme("c", end, end.plusSeconds(3600), "Plain")
                store.saveGuide("g", java.time.Instant.parse("2026-10-11T00:00:00Z"), emptyList(), listOf(rich, plain))
                assertEquals(listOf(rich, plain), store.programmes("g", listOf("c"), start, end.plusSeconds(3600)).getValue("c"))
            }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT title, details_json FROM guide_programme ORDER BY starts_at").use { rows ->
                        assertTrue(rows.next()); assertEquals("New", rows.getString(1)); assertTrue(rows.getString(2).startsWith("{"))
                        assertTrue(rows.next()); assertEquals("Plain", rows.getString(1)); assertNull(rows.getString(2), "a programme without metadata stores none")
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
            assertEquals(listOf(1, 2, 3, 4, 10, 30, 40, 41, 50, 60, 70, 80, 90), foundationMigrations().map { it.version })
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { rows ->
                        val tables = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertTrue(
                            tables.containsAll(listOf("lineup_entry", "lineup_offering", "guide_channel", "guide_programme", "guide_import", "logo_cache", "station_availability", "policy_change")),
                            tables.toString(),
                        )
                        assertTrue(tables.none { it.startsWith("curation") }, tables.toString())
                    }
                }
            }
        } finally { closeDatabase(db); dir.toFile().deleteRecursively() }
    }
}
