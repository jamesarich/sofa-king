package dev.jamesrich.sofaking.backend.store

import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

internal data class Migration(val version: Int, val sql: String)

internal fun foundationMigrations(): List<Migration> = listOf(
    "V001__foundation.sql", "V002__offering_health.sql",
).mapIndexed { index, resource ->
    val sql = checkNotNull(Migration::class.java.getResourceAsStream("/db/$resource")) {
        "Missing migration $resource"
    }.bufferedReader().use { it.readText() }
    Migration(index + 1, sql)
}

/** Our migration files contain simple SQL statements, with no embedded semicolons. */
internal fun applyMigrations(database: Database, migrations: List<Migration>) {
    require(migrations.map { it.version } == (1..migrations.size).toList())
    transaction(database) {
        maxAttempts = 1
        exec("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)")
        val applied = exec("SELECT version FROM schema_version ORDER BY version") { rows ->
            buildList { while (rows.next()) add(rows.getInt(1)) }
        }.orEmpty()
        check(applied == (1..applied.size).toList() && applied.size <= migrations.size) {
            "Unsupported database schema $applied; expected versions 1..${migrations.size}. Use a compatible server or restore a backup."
        }
        for (migration in migrations.drop(applied.size)) {
            migration.sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach { exec(it) }
            exec("INSERT INTO schema_version VALUES (${migration.version}, '${Instant.now()}')")
        }
    }
}
