package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseBackupsTest {
    private val clock = MutableClock()

    private fun withStore(block: suspend (Path, BackendStore, DatabaseBackups, () -> Int) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("sofa-backups-")
        val store = openStore(dir, clock)
        var restarts = 0
        val backups = DatabaseBackups(dir, clock, Jobs(clock), { restarts++ }, store::schemaVersion, keep = 2)
        try {
            block(dir, store, backups) { restarts }
        } finally {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a backup is a whole copy of the running database, listed newest first`() = withStore {
            _,
            store,
            backups,
            _,
        ->
        val first = backups.take()
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        val second = backups.take(BackupKind.DAILY)
        assertEquals("manual-20261010-130000.sqlite", first.name)
        assertEquals(listOf(second.name, first.name), backups.list().map { it.name }, "newest first")
        assertEquals(BackupKind.DAILY, backups.list().single { it.name == second.name }.kind)
        assertTrue(first.bytes > 0)
        val copy = Files.createTempDirectory("sofa-backup-copy-")
        try {
            Files.copy(backups.file(first.name)!!, copy.resolve(DATABASE_FILE))
            val opened = openStore(copy, clock)
            try {
                assertEquals(store.serverId(), opened.serverId(), "the copy opens as the same server")
            } finally {
                opened.close()
            }
        } finally {
            copy.toFile().deleteRecursively()
        }
    }

    @Test fun `pruning keeps the newest daily backups and leaves the others`() = withStore { _, _, backups, _ ->
        val manual = backups.take()
        repeat(4) {
            clock.now = clock.now.plus(Duration.ofDays(1))
            backups.take(BackupKind.DAILY)
        }
        assertEquals(2, backups.prune())
        val left = backups.list()
        assertEquals(2, left.count { it.kind == BackupKind.DAILY })
        assertTrue(left.any { it.name == manual.name }, "a backup taken by hand stays")
    }

    @Test fun `only a name in the backups folder reads as a backup`() = withStore { _, _, backups, _ ->
        backups.take()
        assertNull(backups.file("../backend.sqlite"))
        assertNull(backups.file("missing.sqlite"))
        assertNull(backups.file("manual.txt"))
    }

    @Test fun `a restore stages the backup and restarts, and the next start swaps it in`() =
        withStore { dir, store, backups, restarts ->
            val backup = backups.take()
            val serverId = store.serverId()
            backups.restore(backup.name)
            assertEquals(1, restarts())
            assertTrue(dir.resolve(RESTORE_FILE).exists())
            store.close()

            applyPendingRestore(dir, clock)
            assertFalse(dir.resolve(RESTORE_FILE).exists())
            val aside = Files.list(dir.resolve(BACKUP_FOLDER)).use { files ->
                files.map {
                    it.fileName.toString()
                }.filter { it.startsWith("before-restore-") && it.endsWith(".sqlite") }
                    .toList()
            }
            assertEquals(1, aside.size, "the database it replaced is kept")
            val reopened = openStore(dir, clock)
            try {
                assertEquals(serverId, reopened.serverId())
            } finally {
                reopened.close()
            }
        }

    @Test fun `a file that is not this server's database is refused before anything changes`() =
        withStore { dir, _, backups, restarts ->
            val text = assertFailsWith<RestoreRefused> { backups.restore("not a database".byteInputStream()) }
            assertEquals("The file is not an SQLite database.", text.message)
            val foreign = Files.createTempFile("sofa-foreign-", ".sqlite")
            try {
                org.sqlite.SQLiteConfig().createConnection("jdbc:sqlite:$foreign").use { connection ->
                    connection.createStatement().use { it.execute("CREATE TABLE notes(text TEXT)") }
                }
                assertFailsWith<RestoreRefused> { Files.newInputStream(foreign).use { backups.restore(it) } }
            } finally {
                Files.deleteIfExists(foreign)
            }
            assertEquals(0, restarts())
            assertFalse(dir.resolve(RESTORE_FILE).exists())
        }
}
