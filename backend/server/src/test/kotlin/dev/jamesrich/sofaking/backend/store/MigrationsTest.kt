package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.PolicyChange
import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.PreviouslyShown
import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import dev.jamesrich.sofaking.backend.catalog.StationAvailability
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lineup entry the upgrade tests store before the upgrade. */
private const val ENTRY = "b0f6d2f4-0000-4000-8000-000000000001"

class MigrationsTest {
    @Test fun `failed migration rolls back schema and version atomically`() {
        val dir = Files.createTempDirectory("sofa-migration-test-")
        val db = databaseAt(dir)
        try {
            assertFails { applyMigrations(db, listOf(Migration(1, "CREATE TABLE partial(value TEXT); INVALID SQL"))) }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN " +
                            "('partial','schema_version')",
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0, rows.getInt(1))
                    }
                }
            }
        } finally {
            closeDatabase(db)
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `versions may have gaps and a later binary applies only what is missing`() {
        val dir = Files.createTempDirectory("sofa-migration-gap-")
        val db = databaseAt(dir)
        try {
            applyMigrations(
                db,
                listOf(Migration(1, "CREATE TABLE one(v TEXT)"), Migration(10, "CREATE TABLE ten(v TEXT)")),
            )
            applyMigrations(
                db,
                listOf(
                    Migration(1, "CREATE TABLE one(v TEXT)"),
                    Migration(4, "CREATE TABLE four(v TEXT)"),
                    Migration(10, "CREATE TABLE ten(v TEXT)"),
                ),
            )
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT version FROM schema_version ORDER BY version").use { rows ->
                        val versions = buildList { while (rows.next()) add(rows.getInt(1)) }
                        assertEquals(listOf(1, 4, 10), versions)
                    }
                }
            }
            assertFails("an older binary must refuse a newer schema") {
                applyMigrations(db, listOf(Migration(1, "CREATE TABLE one(v TEXT)")))
            }
            assertFails { applyMigrations(db, listOf(Migration(10, ""), Migration(1, ""))) }
        } finally {
            closeDatabase(db)
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Migrates a database in a new directory named from [prefix] to just before [version], adds [rows], then runs
     * [block] on the directory, which is deleted after.
     */
    private fun upgradeFrom(
        prefix: String,
        version: Int,
        rows: JdbcTransaction.() -> Unit,
        block: suspend (Path) -> Unit,
    ) = runBlocking<Unit> {
        val dir = Files.createTempDirectory(prefix)
        try {
            val db = databaseAt(dir)
            try {
                applyMigrations(db, foundationMigrations().filter { it.version < version })
                transaction(db) { rows() }
            } finally {
                closeDatabase(db)
            }
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Runs [block] on a plain JDBC statement over the database in [dir]. */
    private fun <T> withStatement(dir: Path, block: (Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
            connection.createStatement().use(block)
        }

    /** The first column of every row [sql] selects, as integers. */
    private fun Statement.ints(sql: String): List<Int> =
        executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getInt(1)) } }

    @Test
    fun `a lineup written before the created-by and auto-disable columns opens with defaults after the upgrade`() =
        upgradeFrom("sofa-migration-upgrade-", 40, {
            exec(
                "INSERT INTO lineup_entry(id, position, number, name, group_name, logo, enabled, " +
                    "guide_id, guide_channel, updated_at) VALUES ('$ENTRY', " +
                    "0, '5', 'First', 'News', NULL, 1, 'g', 'x', '2026-10-10T00:00:00Z')",
            )
            exec("INSERT INTO lineup_offering(entry_id, position, offering_id) VALUES ('$ENTRY', 0, 'o1')")
        }) { dir ->
            openStore(dir).use { store ->
                val entry = store.lineup().single()
                assertEquals(
                    LineupEntryRecord(ENTRY, "5", "First", "News", null, true, GuideMapping("g", "x"), listOf("o1")),
                    entry,
                )
                assertEquals("manual", entry.createdBy)
                assertNull(entry.autoDisabledAt)
                assertNull(entry.autoDisabledReason)
                assertTrue(store.policyChanges(10).isEmpty())
                assertTrue(store.stationAvailability().isEmpty())
                val remembered = mapOf(
                    "news" to StationAvailability(
                        Instant.parse("2026-10-10T00:00:00Z"),
                        true,
                        lastNumber = "5",
                        lastName = "News",
                        pinOfferings = mapOf("p/tvg-id:n" to listOf("o1")),
                    ),
                    "gone" to StationAvailability(missingSince = Instant.parse("2026-10-09T00:00:00Z")),
                )
                store.saveStationAvailability(remembered, Instant.parse("2026-10-10T01:00:00Z"))
                assertEquals(remembered, store.stationAvailability())
                store.saveStationAvailability(
                    mapOf("news" to remembered.getValue("news")),
                    Instant.parse("2026-10-10T02:00:00Z"),
                )
                assertEquals(setOf("news"), store.stationAvailability().keys, "a save replaces every row")
            }
            assertEquals(
                listOf(
                    1, 2, 3, 4, 10, 30, 40, 41, 50, 60, 70, 80, 90, 100, 110, 120, 121, 200, 201, 202,
                    203, 204, 205, 206, 207, 208, 209, 210, 211, 213, 214, 215, 216, 217, 218, 219,
                ),
                withStatement(dir) { it.ints("SELECT version FROM schema_version ORDER BY version") },
            )
        }

    /** A policy-built entry, the curation rows of the version before 70, and a curation change of each kind. */
    private fun JdbcTransaction.curationRows() {
        exec(
            "INSERT INTO lineup_entry(id, position, number, name, group_name, logo, enabled, guide_id, " +
                "guide_channel, updated_at, pinned, created_by, auto_disabled_at, auto_disabled_reason) " +
                "VALUES ('$ENTRY', 0, '5', 'First', 'News', NULL, 0, " +
                "NULL, NULL, '2026-10-10T00:00:00Z', 'group,name', 'policy', " +
                "'2026-10-09T00:00:00Z', 'unavailable')",
        )
        exec("INSERT INTO lineup_offering(entry_id, position, offering_id) VALUES ('$ENTRY', 0, 'o1')")
        exec("INSERT INTO curation_rule(id, kind, position, rule_json) VALUES ('r', 'group', 0, '{}')")
        exec(
            "INSERT INTO curation_entry_state(entry_id, unavailable_since) VALUES ('$ENTRY', '2026-10-09T00:00:00Z')",
        )
        exec(
            "INSERT INTO curation_adopted(channel_id, entry_id, adopted_at) VALUES ('c', " +
                "'$ENTRY', '2026-10-09T00:00:00Z')",
        )
        exec("INSERT INTO metadata(key, value) VALUES ('curation:settings', '{}')")
        exec(
            """INSERT INTO curation_change(applied_at, entry_id, action_json) VALUES """ +
                """('2026-10-09T00:00:00Z', NULL, """ +
                """'{"kind":"regroup","entryName":"Old","reason":"rule"}')""",
        )
        exec(
            """INSERT INTO curation_change(applied_at, entry_id, action_json) VALUES """ +
                """('2026-10-10T00:00:00Z', '$ENTRY', """ +
                """'{"kind":"policy","entryId":"$ENTRY","entryName":"First",""" +
                """"field":"enabled","before":"true","after":"false","reason":"health: """ +
                """unavailable","ruleId":null,"offeringIds":[],"key":"first"}')""",
        )
    }

    @Test fun `the upgrade keeps the lineup and the policy changes from the curation tables`() =
        upgradeFrom("sofa-migration-curation-", 70, { curationRows() }) { dir ->
            openStore(dir).use { store ->
                assertEquals(
                    LineupEntryRecord(
                        ENTRY, "5", "First", "News", enabled = false,
                        offeringIds = listOf("o1"),
                        createdBy = "policy",
                        autoDisabledAt = Instant.parse("2026-10-09T00:00:00Z"),
                        autoDisabledReason = "unavailable",
                    ),
                    store.lineup().single(),
                )
                val change = store.policyChanges(10).single()
                assertEquals("2026-10-10T00:00:00Z", change.appliedAt)
                assertEquals(
                    PolicyChange(ENTRY, "First", "enabled", "true", "false", "health: unavailable", "first"),
                    change.change,
                )
            }
        }

    @Test fun `the upgrade drops the curation tables, their settings and the pinned column`() =
        upgradeFrom("sofa-migration-curation-", 70, { curationRows() }) { dir ->
            openStore(dir).close()
            withStatement(dir) { stmt ->
                stmt.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE 'curation%'").use { rows ->
                    assertFalse(rows.next(), "no curation table or index is left")
                }
                assertEquals(listOf(0), stmt.ints("SELECT COUNT(*) FROM metadata WHERE key = 'curation:settings'"))
                assertEquals(
                    listOf(0),
                    stmt.ints("SELECT COUNT(*) FROM pragma_table_info('lineup_entry') WHERE name = 'pinned'"),
                )
            }
        }

    @Test fun `programs stored before the details column read with no metadata, and new ones keep theirs`() =
        upgradeFrom("sofa-migration-details-", 80, {
            exec("INSERT INTO guide_import(guide_id, imported_at) VALUES ('g', '2026-10-10T00:00:00Z')")
            exec(
                "INSERT INTO guide_programme(guide_id, channel_id, starts_at, ends_at, title, " +
                    "description) VALUES ('g', 'c', 1791640800, 1791644400, 'Old', 'Kept')",
            )
        }) { dir ->
            val start = Instant.ofEpochSecond(1791640800)
            val end = Instant.ofEpochSecond(1791644400)
            openStore(dir).use { store ->
                assertEquals(
                    GuideProgram("c", start, end, "Old", "Kept"),
                    store.programs("g", listOf("c"), start, end).getValue("c").single(),
                )
                val rich = GuideProgram(
                    "c", start, end, "New", subTitle = "Part One",
                    episodeNumbers = listOf(EpisodeNumber("xmltv_ns", "0.1.")),
                    icon = "https://img.test/p.jpg",
                    categories = listOf("News"),
                    rating = ProgramRating("TV-G", "VCHIP"), isNew = true,
                    previouslyShown = PreviouslyShown(), date = "2026", language = "en",
                )
                val plain = GuideProgram("c", end, end.plusSeconds(3600), "Plain")
                store.saveGuide("g", Instant.parse("2026-10-11T00:00:00Z"), emptyList(), listOf(rich, plain))
                assertEquals(
                    listOf(rich, plain),
                    store.programs("g", listOf("c"), start, end.plusSeconds(3600)).getValue("c"),
                )
            }
            withStatement(dir) { stmt ->
                stmt.executeQuery("SELECT title, details_json FROM guide_program ORDER BY starts_at").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("New", rows.getString(1))
                    assertTrue(rows.getString(2).startsWith("{"))
                    assertTrue(rows.next())
                    assertEquals("Plain", rows.getString(1))
                    assertNull(rows.getString(2), "a program without metadata stores none")
                }
            }
        }

    @Test fun `the guide table and a canceled recording move to their US names with their rows`() =
        upgradeFrom("sofa-migration-us-names-", 208, {
            exec("INSERT INTO guide_import(guide_id, imported_at) VALUES ('g', '2026-10-10T00:00:00Z')")
            exec(
                "INSERT INTO guide_programme(guide_id, channel_id, starts_at, ends_at, title) " +
                    "VALUES ('g', 'c', 1791640800, 1791644400, 'Kept')",
            )
            exec(
                "INSERT INTO recording(id, channel_id, channel_name, starts_at, ends_at, " +
                    "start_padding_seconds, end_padding_seconds, title, movie, state, bytes, protected, " +
                    "position_ms, watched, created_at, updated_at) " +
                    "VALUES ('r', 'c', 'C', 1791640800, 1791644400, 0, 0, 'Kept', 0, 'canceled', 0, 0, 0, 0, " +
                    "'2026-10-10T00:00:00Z', '2026-10-10T00:00:00Z')",
            )
        }) { dir ->
            val start = Instant.ofEpochSecond(1791640800)
            val end = Instant.ofEpochSecond(1791644400)
            openStore(dir).use { store ->
                assertEquals(
                    GuideProgram("c", start, end, "Kept"),
                    store.programs("g", listOf("c"), start, end).getValue("c").single(),
                )
            }
            withStatement(dir) { stmt ->
                stmt.executeQuery("SELECT state FROM recording WHERE id = 'r'").use { rows ->
                    rows.next()
                    assertEquals("canceled", rows.getString(1))
                }
                stmt.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE 'guide_programme%'").use { rows ->
                    assertFalse(rows.next(), "no British table or index is left")
                }
                assertEquals(
                    listOf(1),
                    stmt.ints(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'guide_program_window'",
                    ),
                )
            }
        }

    @Test fun `shipped migrations open and carry the lineup and guide tables`() {
        val dir = Files.createTempDirectory("sofa-migration-shipped-")
        val db = databaseAt(dir)
        try {
            applyMigrations(db, foundationMigrations())
            assertEquals(
                listOf(
                    1, 2, 3, 4, 10, 30, 40, 41, 50, 60, 70, 80, 90, 100, 110, 120, 121, 200, 201, 202, 203, 204, 205,
                    206, 207, 208, 209, 210, 211, 213, 214, 215, 216, 217, 218, 219,
                ),
                foundationMigrations().map {
                    it.version
                },
            )
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("backend.sqlite")}").use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { rows ->
                        val tables = buildList { while (rows.next()) add(rows.getString(1)) }
                        assertTrue(
                            tables.containsAll(
                                listOf(
                                    "lineup_entry",
                                    "lineup_offering",
                                    "guide_channel",
                                    "guide_program",
                                    "guide_import",
                                    "logo_cache",
                                    "station_availability",
                                    "policy_change",
                                ),
                            ),
                            tables.toString(),
                        )
                        assertTrue(tables.none { it.startsWith("curation") }, tables.toString())
                    }
                }
            }
        } finally {
            closeDatabase(db)
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `every db migration file is listed in Migrations, in version order`() {
        // Tests run in the server's project directory; the files are the sources, not what the last build copied.
        val folder = java.nio.file.Path.of("src/main/resources/db")
        assertTrue(Files.isDirectory(folder), "no migrations folder at ${folder.toAbsolutePath()}")
        val files = Files.list(folder).use { paths ->
            paths.map { it.fileName.toString() }.filter { it.startsWith("V") && it.endsWith(".sql") }.toList()
        }
        val named = Regex("""V(\d+)__\w+\.sql""")
        assertEquals(emptyList(), files.filterNot(named::matches), "migration files not named Vnnn__name.sql")
        val onDisk = files.sortedBy { named.matchEntire(it)!!.groupValues[1].toInt() }
        assertEquals(
            onDisk.size,
            onDisk.map {
                named.matchEntire(it)!!.groupValues[1].toInt()
            }.distinct().size,
            "two files share a version: $onDisk",
        )
        val listed = foundationMigrations()
        assertEquals(
            onDisk.map {
                named.matchEntire(it)!!.groupValues[1].toInt()
            },
            listed.map { it.version },
            "Migrations.kt lists other versions than the files: $onDisk",
        )
        for ((file, migration) in onDisk.zip(
            listed,
        )) {
            assertEquals(
                Files.readString(folder.resolve(file)),
                migration.sql,
                "$file is not the migration listed as version ${migration.version}",
            )
        }
    }
}
