package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** The database file in the data directory. */
internal const val DATABASE_FILE = "backend.sqlite"

/** A database waiting in the data directory to replace [DATABASE_FILE] when the server next starts. */
internal const val RESTORE_FILE = "restore.sqlite"

/** The folder in the data directory that holds the backups, the updater's included. */
internal const val BACKUP_FOLDER = "backups"

private val log = LoggerFactory.getLogger(DatabaseBackups::class.java)
private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
private val STAMPED = Regex("""^(?:daily|manual|before-restore)-(\d{8}-\d{6})\.sqlite$""")
private val NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.sqlite""")
private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
private const val BUSY_TIMEOUT_MS = 5000

/** How long after the start the first daily backup waits at the least, so it stays out of the startup work. */
private val FIRST_AFTER: Duration = Duration.ofMinutes(10)

/** One database copy in the backups folder: its file [name], what took it, when and how large it is. */
class BackupFile(val name: String, val kind: BackupKind, val takenAt: Instant, val bytes: Long)

/** What took a backup, from the start of its file name. */
enum class BackupKind(val prefix: String, val label: String) {
    DAILY("daily-", "Daily"),
    MANUAL("manual-", "Taken by hand"),
    BEFORE_RESTORE("before-restore-", "Before a restore"),
    UPDATE("backend-", "Before an update"),
    OTHER("", "Other"),
    ;

    companion object {
        fun of(name: String): BackupKind = entries.first { name.startsWith(it.prefix) }
    }
}

/** Why a file cannot be restored, in words for the portal. */
class RestoreRefused(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Online copies of the database in `backups/` under the data directory: a daily one, keeping the newest [keep], one on
 * request, and a restore that stages a copy as [RESTORE_FILE] and restarts the server, which swaps it in before it
 * opens the database ([applyPendingRestore]). A copy is SQLite's `VACUUM INTO` over its own connection, so it is
 * consistent while the server writes, and compact.
 */
class DatabaseBackups(
    private val dataDirectory: Path,
    private val clock: Clock,
    private val jobs: Jobs,
    private val restart: () -> Unit,
    /** The schema the running server knows, so a backup from a newer server is refused. */
    private val schemaVersion: suspend () -> Int,
    val keep: Int = KEEP,
    val every: Duration = Duration.ofDays(1),
    private val firstAfter: Duration = FIRST_AFTER,
) {
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)

    val directory: Path get() = dataDirectory.resolve(BACKUP_FOLDER)

    init {
        jobs.register(JOB, "Database backup", every) { requests.trySend(Unit) }
    }

    /** Every database copy in the backups folder, newest first. */
    suspend fun list(): List<BackupFile> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(directory)) return@withContext emptyList()
        directory.listDirectoryEntries("*.sqlite").filter { it.isRegularFile() && NAME.matches(it.fileName.toString()) }
            .map { file ->
                val name = file.fileName.toString()
                val takenAt = takenAt(name) ?: Files.getLastModifiedTime(file).toInstant()
                BackupFile(name, BackupKind.of(name), takenAt, Files.size(file))
            }
            .sortedByDescending { it.takenAt }
    }

    /** The backup named [name] in the backups folder; null for any other name. */
    fun file(name: String): Path? {
        if (!NAME.matches(name)) return null
        return directory.resolve(name).takeIf { it.isRegularFile() }
    }

    /** Takes a copy now as a backup of [kind]; returns it. */
    suspend fun take(kind: BackupKind = BackupKind.MANUAL): BackupFile = mutex.withLock {
        withContext(Dispatchers.IO) {
            createPrivateDirectory(directory)
            val name = "${kind.prefix}${STAMP.format(clock.instant())}.sqlite"
            val target = directory.resolve(name)
            val partial = directory.resolve(".$name.partial")
            Files.deleteIfExists(partial)
            try {
                connect(dataDirectory.resolve(DATABASE_FILE), readOnly = false).use { connection ->
                    connection.prepareStatement("VACUUM INTO ?").use { statement ->
                        statement.setString(1, partial.toAbsolutePath().toString())
                        statement.execute()
                    }
                }
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(partial)
            }
            log.info("backup: took name={} bytes={}", name, Files.size(target))
            BackupFile(name, kind, takenAt(name) ?: clock.instant(), Files.size(target))
        }
    }

    /** Drops the daily backups beyond the newest [keep]; returns how many it dropped. */
    suspend fun prune(): Int = withContext(Dispatchers.IO) {
        val old = list().filter { it.kind == BackupKind.DAILY }.drop(keep)
        for (backup in old) Files.deleteIfExists(directory.resolve(backup.name))
        if (old.isNotEmpty()) log.info("backup: dropped old daily backups count={}", old.size)
        old.size
    }

    /** Stages the backup named [name] and restarts; refused when it is missing or not a database this server reads. */
    suspend fun restore(name: String) {
        val file = file(name) ?: throw RestoreRefused("There is no backup named $name.")
        stage { staged -> Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING) }
    }

    /** Stages the uploaded database read from [input] and restarts; refused when it is not one this server reads. */
    suspend fun restore(input: InputStream) {
        stage { staged -> Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING) }
    }

    private suspend fun stage(write: (Path) -> Unit) = mutex.withLock {
        val known = schemaVersion()
        withContext(Dispatchers.IO) {
            val partial = dataDirectory.resolve(".$RESTORE_FILE.partial")
            try {
                write(partial)
                restorable(partial, known)?.let { throw RestoreRefused(it) }
                restrict(partial)
                Files.move(
                    partial,
                    dataDirectory.resolve(RESTORE_FILE),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(partial)
            }
        }
        log.info("backup: staged a restore; restarting to apply it")
        restart()
    }

    /** Runs the daily backup when the newest is a day old, at the earliest [firstAfter] from start, and on request. */
    @OptIn(ExperimentalCoroutinesApi::class) // select { onTimeout }
    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("database-backup")) {
        var wait = maxOf(firstAfter, untilDue()).toMillis()
        while (isActive) {
            select<Unit> {
                requests.onReceive { }
                onTimeout(wait) { }
            }
            jobs.record(JOB) {
                val backup = take(BackupKind.DAILY)
                val dropped = prune()
                JobOutcome(true, backup.name + if (dropped > 0) ", dropped $dropped old" else "")
            }
            wait = every.toMillis()
        }
    }

    private suspend fun untilDue(): Duration {
        val newest = list().firstOrNull { it.kind == BackupKind.DAILY } ?: return Duration.ZERO
        return Duration.between(clock.instant(), newest.takenAt.plus(every)).coerceAtLeast(Duration.ZERO)
    }

    companion object {
        /** The Jobs page's id for the daily backup. */
        const val JOB = "backup"

        /** How many daily backups stay. */
        const val KEEP = 7
    }
}

/** When a backup this class named was taken, from the stamp in [name]; null for a name without one. */
private fun takenAt(name: String): Instant? = STAMPED.find(name)?.let { match ->
    runCatching { Instant.from(STAMP.parse(match.groupValues[1])) }.getOrNull()
}

private fun connect(file: Path, readOnly: Boolean): java.sql.Connection = SQLiteConfig().apply {
    setBusyTimeout(BUSY_TIMEOUT_MS)
    setReadOnly(readOnly)
}.createConnection("jdbc:sqlite:${file.toAbsolutePath()}")

private fun createPrivateDirectory(directory: Path) {
    if (Files.isDirectory(directory)) return
    Files.createDirectories(directory)
    runCatching { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")) }
}

/**
 * Why [file] cannot be restored, or null when it is a whole SQLite database holding this server's schema, no newer
 * than [known].
 */
private fun restorable(file: Path, known: Int): String? {
    val header = Files.newInputStream(file).use { it.readNBytes(SQLITE_HEADER.size) }
    if (!header.contentEquals(SQLITE_HEADER)) return "The file is not an SQLite database."
    val (integrity, version) = try {
        connect(file, readOnly = false).use { connection ->
            connection.createStatement().use { statement ->
                val integrity = statement.first("PRAGMA quick_check")
                val version = statement.takeIf { integrity == "ok" }?.first("SELECT MAX(version) FROM schema_version")
                integrity to (version?.toIntOrNull() ?: 0)
            }
        }
    } catch (e: java.sql.SQLException) {
        throw RestoreRefused("The file is not a Sofa King database.", e)
    }
    return when {
        integrity != "ok" -> "The database is damaged: ${integrity ?: "no answer"}."
        version > known -> "The backup is from a newer server (schema $version, this one runs $known); update first."
        else -> null
    }
}

/** The first column of [sql]'s first row as text; null when it returns no row. */
private fun java.sql.Statement.first(sql: String): String? =
    executeQuery(sql).use { if (it.next()) it.getString(1) else null }

private fun restrict(file: Path) {
    runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
}

/**
 * Swaps a staged [RESTORE_FILE] in for the database in [dataDirectory] before the server opens it. The database it
 * replaces moves to the backups folder as a `before-restore-` backup, its write-ahead log beside it, so nothing is
 * lost.
 */
internal fun applyPendingRestore(dataDirectory: Path, clock: Clock = Clock.systemUTC()) {
    val staged = dataDirectory.resolve(RESTORE_FILE)
    if (!Files.isRegularFile(staged)) return
    val database = dataDirectory.resolve(DATABASE_FILE)
    if (Files.exists(database)) {
        val backups = dataDirectory.resolve(BACKUP_FOLDER)
        Files.createDirectories(backups)
        val name = "${BackupKind.BEFORE_RESTORE.prefix}${STAMP.format(clock.instant())}.sqlite"
        Files.move(database, backups.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        val wal = dataDirectory.resolve("$DATABASE_FILE-wal")
        if (Files.exists(wal)) Files.move(wal, backups.resolve("$name-wal"), StandardCopyOption.REPLACE_EXISTING)
        log.info("backup: moved the old database aside name={}", name)
    }
    Files.deleteIfExists(dataDirectory.resolve("$DATABASE_FILE-wal"))
    Files.deleteIfExists(dataDirectory.resolve("$DATABASE_FILE-shm"))
    Files.move(staged, database, StandardCopyOption.ATOMIC_MOVE)
    log.info("backup: restored the staged database")
}
