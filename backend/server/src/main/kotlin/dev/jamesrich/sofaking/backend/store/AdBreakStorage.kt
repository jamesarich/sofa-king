package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.ads.BreakEntry
import java.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Every entry of the break library. */
suspend fun BackendStore.adBreaks(): List<BreakEntry> = read { AdBreakRows.selectAll().map { it.entry() } }

/** Saves [entries] as they are now, new ones and changed ones alike; they are copies no one else changes. */
suspend fun BackendStore.saveAdBreaks(entries: Collection<BreakEntry>) = query {
    AdBreakRows.batchUpsert(entries) { entry ->
        this[AdBreakRows.id] = entry.id
        this[AdBreakRows.points] = bytes(entry.points)
        this[AdBreakRows.providerId] = entry.providerId
        this[AdBreakRows.channels] = entry.channels.joinToString(",")
        this[AdBreakRows.days] = entry.days.joinToString(",")
        this[AdBreakRows.sourceBreak] = entry.source
        this[AdBreakRows.createdAt] = entry.createdAt.toString()
        this[AdBreakRows.lastHitAt] = entry.lastHitAt?.toString()
        this[AdBreakRows.hits] = entry.hits
        this[AdBreakRows.falseHits] = entry.falseHits
        this[AdBreakRows.blocked] = entry.blocked
    }
    Unit
}

/** Drops the entries [ids] names. */
suspend fun BackendStore.deleteAdBreaks(ids: Collection<String>) = query {
    ids.chunked(500).forEach { chunk -> AdBreakRows.deleteWhere { AdBreakRows.id inList chunk } }
}

private fun ResultRow.entry(): BreakEntry {
    fun list(text: String) = text.split(',').filter { it.isNotEmpty() }.toSet()
    return BreakEntry(
        this[AdBreakRows.id], points(this[AdBreakRows.points]), this[AdBreakRows.providerId], this[AdBreakRows.sourceBreak], Instant.parse(this[AdBreakRows.createdAt]),
        channels = list(this[AdBreakRows.channels]), days = list(this[AdBreakRows.days]), lastHitAt = this[AdBreakRows.lastHitAt]?.let(Instant::parse),
        hits = this[AdBreakRows.hits], falseHits = this[AdBreakRows.falseHits], blocked = this[AdBreakRows.blocked],
    )
}
