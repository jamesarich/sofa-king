package dev.jamesrich.sofaking.backend.store

import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import javax.sql.DataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

private val sources = ConcurrentHashMap<Database, PinnedConnectionSource>()

internal fun databaseAt(directory: Path): Database {
    Files.createDirectories(directory)
    val config = SQLiteConfig().apply {
        enforceForeignKeys(true)
        setBusyTimeout(5000)
        setJournalMode(SQLiteConfig.JournalMode.WAL)
        setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        setJournalSizeLimit(64 * 1024 * 1024)
    }
    val source = PinnedConnectionSource(
        SQLiteDataSource(config).apply { url = "jdbc:sqlite:${directory.toAbsolutePath().resolve("backend.sqlite")}" },
    )
    return Database.connect(source).also { sources[it] = source }
}

/** Unregisters [database] and closes the connection it holds. */
internal fun closeDatabase(database: Database) {
    TransactionManager.closeAndUnregister(database)
    sources.remove(database)?.close()
}

/**
 * Hands out one SQLite connection for the database's life; the close Exposed sends after each transaction is
 * ignored. Every transaction already runs on the store's one thread, so a pool would buy nothing, while opening a
 * connection per transaction re-applies the pragmas and checkpoints the WAL each time the last one closes.
 */
internal class PinnedConnectionSource(private val delegate: SQLiteDataSource) : DataSource, AutoCloseable {
    private var real: Connection? = null
    private var pinned: Connection? = null

    @Synchronized override fun getConnection(): Connection {
        val current = real
        if (current != null && !current.isClosed) return pinned!!
        val opened = delegate.connection
        real = opened
        val proxy = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "close" && method.parameterCount == 0) {
                null
            } else {
                try { method.invoke(opened, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
            }
        } as Connection
        pinned = proxy
        return proxy
    }

    override fun getConnection(username: String?, password: String?): Connection = getConnection()

    @Synchronized override fun close() {
        real?.close()
        real = null
        pinned = null
    }

    override fun getLogWriter(): PrintWriter? = delegate.logWriter
    override fun setLogWriter(out: PrintWriter?) { delegate.logWriter = out }
    override fun setLoginTimeout(seconds: Int) { delegate.loginTimeout = seconds }
    override fun getLoginTimeout(): Int = delegate.loginTimeout
    override fun getParentLogger(): Logger = delegate.parentLogger
    override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)
    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)
}
