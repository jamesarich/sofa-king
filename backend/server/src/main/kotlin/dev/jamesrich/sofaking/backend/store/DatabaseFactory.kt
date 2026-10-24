package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.sqlite.SQLiteConfig
import org.sqlite.javax.SQLiteConnectionPoolDataSource
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import javax.sql.ConnectionEvent
import javax.sql.ConnectionEventListener
import javax.sql.DataSource
import javax.sql.PooledConnection

private val sources = ConcurrentHashMap<Database, PinnedConnectionSource>()

/**
 * Opens the database file in [directory]. With [readOnly] the connection can only read: WAL lets it read the last
 * committed state while the writer's connection is inside a transaction, so it is opened after the writer, which
 * owns the journal mode and the other persistent settings.
 */
internal fun databaseAt(directory: Path, readOnly: Boolean = false): Database {
    Files.createDirectories(directory)
    val config = SQLiteConfig().apply {
        setBusyTimeout(5000)
        if (readOnly) {
            setReadOnly(true)
        } else {
            enforceForeignKeys(true)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            setJournalSizeLimit(64 * 1024 * 1024)
        }
    }
    val source = PinnedConnectionSource(
        SQLiteConnectionPoolDataSource(config).apply {
            url =
                "jdbc:sqlite:${directory.toAbsolutePath().resolve("backend.sqlite")}"
        },
    )
    return Database.connect(source).also { sources[it] = source }
}

/** Unregisters [database] and closes the connection it holds. */
internal fun closeDatabase(database: Database) {
    TransactionManager.closeAndUnregister(database)
    sources.remove(database)?.close()
}

/**
 * Keeps one physical SQLite connection for the database's life and hands out sqlite-jdbc's pooled handles to it:
 * the close Exposed sends after each transaction returns the handle, and the physical connection, with its pragmas,
 * stays open. Every transaction on a connection runs on that connection's one thread, so a larger pool would
 * buy nothing, while opening a connection per transaction re-applies the pragmas and checkpoints the WAL
 * each time the last one closes. A handle that reports a connection error drops the physical connection, so
 * the next transaction reopens it.
 */
internal class PinnedConnectionSource(private val delegate: SQLiteConnectionPoolDataSource) :
    DataSource,
    AutoCloseable {
    private var pooled: PooledConnection? = null

    @Synchronized override fun getConnection(): Connection {
        val current = pooled ?: delegate.pooledConnection.also { opened ->
            opened.addConnectionEventListener(object : ConnectionEventListener {
                override fun connectionClosed(event: ConnectionEvent) = Unit
                override fun connectionErrorOccurred(event: ConnectionEvent) = drop(opened)
            })
            pooled = opened
        }
        return current.connection
    }

    override fun getConnection(username: String?, password: String?): Connection = getConnection()

    @Synchronized private fun drop(broken: PooledConnection) {
        if (pooled !== broken) return
        pooled = null
        runCatching { broken.close() }
    }

    @Synchronized override fun close() {
        pooled?.close()
        pooled = null
    }

    override fun getLogWriter(): PrintWriter? = delegate.logWriter
    override fun setLogWriter(out: PrintWriter?) {
        delegate.logWriter = out
    }
    override fun setLoginTimeout(seconds: Int) {
        delegate.loginTimeout = seconds
    }
    override fun getLoginTimeout(): Int = delegate.loginTimeout
    override fun getParentLogger(): Logger = delegate.parentLogger
    override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)
    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)
}
