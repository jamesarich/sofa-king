package dev.jamesrich.sofaking.backend.store

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.exposed.v1.jdbc.Database
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

internal fun databaseAt(directory: Path): Database {
    Files.createDirectories(directory)
    val config = SQLiteConfig().apply {
        enforceForeignKeys(true)
        setBusyTimeout(5000)
        setJournalMode(SQLiteConfig.JournalMode.WAL)
        setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        setJournalSizeLimit(64 * 1024 * 1024)
    }
    val source = SQLiteDataSource(config).apply {
        url = "jdbc:sqlite:${directory.toAbsolutePath().resolve("backend.sqlite")}"
    }
    return Database.connect(source)
}
