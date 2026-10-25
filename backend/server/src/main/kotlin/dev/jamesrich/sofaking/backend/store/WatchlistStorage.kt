package dev.jamesrich.sofaking.backend.store

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/** The household's watchlist, newest first. */
suspend fun BackendStore.watchlist(): List<StoredWatchlistEntry> = read {
    WatchlistEntries.selectAll().where { WatchlistEntries.scope eq HOUSEHOLD }
        .orderBy(WatchlistEntries.addedAt, SortOrder.DESC)
        .map {
            StoredWatchlistEntry(
                it[WatchlistEntries.id],
                it[WatchlistEntries.kind],
                it[WatchlistEntries.itemId],
                it[WatchlistEntries.title],
                it[WatchlistEntries.channelId],
                it[WatchlistEntries.addedAt],
            )
        }
}

suspend fun BackendStore.addWatchlistEntry(entry: StoredWatchlistEntry) = query {
    WatchlistEntries.insert {
        it[id] = entry.id
        it[scope] = HOUSEHOLD
        it[kind] = entry.kind
        it[itemId] = entry.itemId
        it[title] = entry.title
        it[channelId] = entry.channelId
        it[addedAt] = entry.addedAt
    }
    Unit
}

/** Takes the entry [id] off the household's list; false when it holds none. */
suspend fun BackendStore.deleteWatchlistEntry(id: String): Boolean = query {
    WatchlistEntries.deleteWhere { (WatchlistEntries.id eq id) and (scope eq HOUSEHOLD) } > 0
}
