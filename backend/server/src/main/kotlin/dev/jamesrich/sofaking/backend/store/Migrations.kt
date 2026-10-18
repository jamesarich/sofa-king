package dev.jamesrich.sofaking.backend.store

import java.time.Clock
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

internal data class Migration(val version: Int, val sql: String)

/** Versions come from the `Vnnn__` file prefix and need not be contiguous. */
internal fun foundationMigrations(): List<Migration> = listOf(
    "V001__foundation.sql", "V002__offering_health.sql", "V003__probe_attempt_and_indexes.sql", "V004__offering_protocol.sql",
    "V010__lineup_and_guides.sql", "V030__logo_cache.sql", "V040__lineup_entry_curation.sql", "V041__curation.sql", "V050__provider_state.sql",
    "V060__station_availability.sql", "V070__drop_curation.sql", "V080__programme_details.sql", "V090__attention_ignore.sql", "V100__runtime_setting.sql", "V110__viewer.sql",
    "V120__recordings.sql",
    "V200__library.sql", "V201__library_metadata.sql",
).map { resource ->
    val sql = checkNotNull(Migration::class.java.getResourceAsStream("/db/$resource")) {
        "Missing migration $resource"
    }.bufferedReader().use { it.readText() }
    Migration(resource.substring(1, resource.indexOf("__")).toInt(), sql)
}

/** Our migration files contain simple SQL statements, with no embedded semicolons. */
internal fun applyMigrations(database: Database, migrations: List<Migration>, clock: Clock = Clock.systemUTC()) {
    val versions = migrations.map { it.version }
    require(versions == versions.sorted() && versions.distinct().size == versions.size) { "Migrations must be unique and in order" }
    transaction(database) {
        maxAttempts = 1
        exec("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)")
        val applied = exec("SELECT version FROM schema_version ORDER BY version") { rows ->
            buildList { while (rows.next()) add(rows.getInt(1)) }
        }.orEmpty().toSet()
        check(applied.all { it in versions }) {
            "Unsupported database schema $applied; this server knows versions $versions. Use a compatible server or restore a backup."
        }
        for (migration in migrations.filter { it.version !in applied }) {
            migration.sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach { exec(it) }
            exec("INSERT INTO schema_version VALUES (${migration.version}, '${clock.instant()}')")
        }
    }
}
