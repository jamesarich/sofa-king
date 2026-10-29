package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.health.HealthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.get
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.name
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A transaction holding its connection longer than this is logged; on one writer, everything behind it waited too. */
private const val SLOW_TRANSACTION_MS = 500L

/**
 * Keys in one `IN` list, or rows in one upsert: SQLite binds at most 32,766 variables per statement, and a library
 * row binds dozens.
 */
internal const val ROW_CHUNK = 500

/** How long closing waits for `PRAGMA optimize`. */
private const val OPTIMIZE_TIMEOUT_SECONDS = 10L

private val log = LoggerFactory.getLogger(BackendStore::class.java)

interface IdentityStore {
    suspend fun serverId(): String
    suspend fun ready(): Boolean
}

/** One SQLite connection and the one thread that runs its transactions. */
internal class DatabaseLane(val name: String) {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, name) }
    val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
}

/**
 * Serializes short SQLite transactions; never owns network/media work. Writes, and reads that must sit inside a write,
 * run in order on the writer; [read] runs on a second, read-only connection, so a long write never holds up a read.
 */
class BackendStore internal constructor(
    private val database: Database,
    private val writer: DatabaseLane,
    private val reader: Database,
    private val readLane: DatabaseLane,
    internal val clock: Clock,
) : IdentityStore,
    AutoCloseable {
    private val closed = AtomicBoolean(false)

    /**
     * Mirror of provider_state; [ProviderStateStorage] replaces entries after each commit, so a
     * tune never reads SQLite for it.
     */
    internal val providerStates = AtomicReference<Map<Pair<String, String>, ProviderStateValue>>(emptyMap())

    /**
     * Mirror of offering_health; only [writeHealth] changes it, after the DB commit, so health reads never touch
     * SQLite.
     */
    internal val healthMirror = HealthMirror()

    internal suspend fun initialize() = databaseWork {
        applyMigrations(database, foundationMigrations(), clock)
        transaction(database) {
            Metadata.insertIgnore {
                it[key] = "server_id"
                it[value] = UUID.randomUUID().toString()
            }
            healthMirror.load()
            providerStates.set(
                ProviderStates.selectAll().associate { row ->
                    (
                        row[ProviderStates.providerId] to
                            row[ProviderStates.key]
                        ) to
                        row.providerState()
                },
            )
        }
    }

    override suspend fun serverId(): String = query {
        Metadata.selectAll().where { Metadata.key eq "server_id" }.single()[Metadata.value]
    }

    override suspend fun ready(): Boolean {
        if (closed.get()) return false
        return try {
            query { exec("SELECT 1") { it.next() && it.getInt(1) == 1 } == true }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** The newest schema migration applied to the database. */
    suspend fun schemaVersion(): Int = query {
        exec("SELECT MAX(version) FROM schema_version") { if (it.next()) it.getInt(1) else 0 } ?: 0
    }

    internal suspend fun <T> query(block: JdbcTransaction.() -> T): T = databaseWork {
        timedTransaction(block) {
            transaction(database) {
                maxAttempts = 1
                block()
            }
        }
    }

    /**
     * Runs [block] in one transaction, then [publish] on the same thread after the commit, so
     * mirrors change in commit order.
     */
    internal suspend fun <T> queryThen(block: JdbcTransaction.() -> T, publish: (T) -> Unit): T = databaseWork {
        timedTransaction(block) {
            transaction(database) {
                maxAttempts = 1
                block()
            }
        }.also(publish)
    }

    /** Runs [block] on the read-only connection: the last committed state, never waiting behind a write. */
    internal suspend fun <T> read(block: JdbcTransaction.() -> T): T {
        check(!closed.get()) { "Store is closed" }
        return withContext(readLane.dispatcher) {
            check(!closed.get()) { "Store is closed" }
            timedTransaction(block) {
                transaction(reader, readOnly = true) {
                    maxAttempts = 1
                    block()
                }
            }
        }
    }

    /** Runs [block] in one transaction, then publishes the rows it changed; a rolled-back write publishes nothing. */
    internal suspend fun <T> writeHealth(block: JdbcTransaction.(MutableMap<String, HealthState>) -> T): T =
        databaseWork {
            val changed = LinkedHashMap<String, HealthState>()
            val result = transaction(database) {
                maxAttempts = 1
                block(changed)
            }
            healthMirror.publish(changed)
            result
        }

    private suspend fun <T> databaseWork(block: () -> T): T {
        check(!closed.get()) { "Store is closed" }
        return withContext(writer.dispatcher) {
            check(!closed.get()) { "Store is closed" }
            block()
        }
    }

    /** Lets SQLite refresh the statistics its query planner uses, on the tables that changed enough to need it. */
    suspend fun optimize() = query {
        exec("PRAGMA optimize")
        Unit
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // SQLite advises PRAGMA optimize before closing a long-lived connection; a failure must not stop the close.
        try {
            writer.executor.submit {
                transaction(database) {
                    maxAttempts = 1
                    exec("PRAGMA optimize")
                }
            }.get(OPTIMIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Closing matters more than the statistics.
        }
        // The reader closes first, so the writer's connection is the last and checkpoints the WAL.
        val readStopped = readLane.stop()
        closeDatabase(reader)
        val writeStopped = writer.stop()
        closeDatabase(database)
        check(readStopped && writeStopped) { "Database work did not stop" }
    }
}

/**
 * Logs a transaction that held its connection for longer than [SLOW_TRANSACTION_MS],
 * named by the file its block is in.
 */
private inline fun <T> timedTransaction(block: Any, work: () -> T): T {
    val started = System.nanoTime()
    try {
        return work()
    } finally {
        val ms = (System.nanoTime() - started) / 1_000_000
        if (ms > SLOW_TRANSACTION_MS) {
            log.warn(
                "slow database transaction source={} durationMs={}",
                block.javaClass.name.substringAfterLast('.').substringBefore('$'),
                ms,
            )
        }
    }
}

private fun DatabaseLane.stop(): Boolean {
    executor.shutdown()
    val stopped = executor.awaitTermination(10, TimeUnit.SECONDS)
    dispatcher.close()
    return stopped
}

suspend fun openStore(dataDirectory: Path, clock: Clock = Clock.systemUTC()): BackendStore {
    // A restore the portal staged replaces the database before anything opens it.
    withContext(Dispatchers.IO) { applyPendingRestore(dataDirectory, clock) }
    val writer = DatabaseLane("sofa-sqlite")
    val readLane = DatabaseLane("sofa-sqlite-read")
    var database: Database? = null
    var store: BackendStore? = null
    var opened = false
    try {
        database = withContext(writer.dispatcher) { databaseAt(dataDirectory) }
        // The writer creates the file and sets WAL before the read-only connection opens it.
        withContext(writer.dispatcher) {
            transaction(database) {
                maxAttempts = 1
                exec("SELECT 1")
            }
        }
        withContext(Dispatchers.IO) { restrictDataAccess(dataDirectory) }
        val reader = withContext(readLane.dispatcher) { databaseAt(dataDirectory, readOnly = true) }
        store = BackendStore(database, writer, reader, readLane, clock)
        store.initialize()
        opened = true
        return store
    } finally {
        // A store that failed to open closes what it had.
        when {
            opened -> Unit

            store != null -> store.close()

            else -> {
                readLane.stop()
                writer.stop()
                database?.let(::closeDatabase)
            }
        }
    }
}
