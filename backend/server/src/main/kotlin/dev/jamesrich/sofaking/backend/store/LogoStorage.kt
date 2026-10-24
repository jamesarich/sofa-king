package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.get
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

suspend fun BackendStore.logos(): List<LogoRecord> = read {
    LogoCacheRows.selectAll().map { row ->
        LogoRecord(
            key = row[LogoCacheRows.key],
            sourceUrl = row[LogoCacheRows.sourceUrl],
            contentType = row[LogoCacheRows.contentType],
            size = row[LogoCacheRows.size],
            contentHash = row[LogoCacheRows.contentHash],
            fetchedAt = row[LogoCacheRows.fetchedAt]?.let(Instant::parse),
            failedAt = row[LogoCacheRows.failedAt]?.let(Instant::parse),
            failures = row[LogoCacheRows.failures],
        )
    }
}

suspend fun BackendStore.saveLogo(record: LogoRecord) = query {
    LogoCacheRows.upsert {
        it[key] = record.key
        it[sourceUrl] = record.sourceUrl
        it[contentType] = record.contentType
        it[size] = record.size
        it[contentHash] = record.contentHash
        it[fetchedAt] = record.fetchedAt?.toString()
        it[failedAt] = record.failedAt?.toString()
        it[failures] = record.failures
    }
    Unit
}

suspend fun BackendStore.deleteLogo(key: String) = query {
    LogoCacheRows.deleteWhere { LogoCacheRows.key eq key }
    Unit
}
