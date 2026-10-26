package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.GuideService
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.catalog.toWire
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.StoredWatchlistEntry
import dev.jamesrich.sofaking.backend.store.addWatchlistEntry
import dev.jamesrich.sofaking.backend.store.deleteWatchlistEntry
import dev.jamesrich.sofaking.backend.store.watchlist
import dev.jamesrich.sofaking.contract.AddWatchlistEntry
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.Program
import dev.jamesrich.sofaking.contract.Watchlist
import dev.jamesrich.sofaking.contract.WatchlistEntry
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val TITLE_LIMIT = 200
private const val ENTRY_LIMIT = 500

/** How far ahead a title entry's next airing is looked for. */
private val AIRING_HORIZON = Duration.ofDays(7)

/**
 * The household's watchlist under `/api/v1/watchlist`: library items to watch and guide titles to watch for. A title
 * entry carries the next airing the guide holds for it in the coming week, on its channel when it names one or on any
 * enabled channel, matched ignoring case and outer spaces as a series rule is. [catalog] is null without libraries.
 */
class WatchlistService internal constructor(
    private val store: BackendStore,
    private val lineup: LineupService,
    private val guides: GuideService,
    private val catalog: LibraryCatalog?,
    private val clock: Clock,
) {
    suspend fun list(): Watchlist {
        val entries = store.watchlist()
        val items = catalog?.present(entries.mapNotNull { it.itemId })?.associateBy { it.id }.orEmpty()
        val titles = entries.filter { it.kind == Wire.WatchlistKind.TITLE }
        val next = if (titles.isEmpty()) emptyMap() else nextAirings(titles, clock.instant())
        return Watchlist(entries.map { it.toWire(it.itemId?.let(items::get), next[it.id]) })
    }

    suspend fun add(request: AddWatchlistEntry): WatchlistEntry {
        val entries = store.watchlist()
        val entry = when (request.kind) {
            Wire.WatchlistKind.ITEM -> itemEntry(request, entries)
            Wire.WatchlistKind.TITLE -> titleEntry(request, entries)
            else -> throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "kind is item or title")
        }
        if (entries.any { it.id == entry.id }) return listed(entry)
        if (entries.size >= ENTRY_LIMIT) {
            throw ApiFailure(
                HttpStatusCode.Conflict,
                "WATCHLIST_FULL",
                "The watchlist holds at most $ENTRY_LIMIT entries",
            )
        }
        store.addWatchlistEntry(entry)
        return listed(entry)
    }

    suspend fun remove(id: String) {
        if (!store.deleteWatchlistEntry(id)) {
            throw ApiFailure(HttpStatusCode.NotFound, "WATCHLIST_ENTRY_NOT_FOUND", "No such watchlist entry")
        }
    }

    /** The entry for a library item, or the one the list already holds for it. */
    private suspend fun itemEntry(
        request: AddWatchlistEntry,
        entries: List<StoredWatchlistEntry>,
    ): StoredWatchlistEntry {
        val id = request.itemId ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "itemId is required")
        entries.firstOrNull { it.itemId == id }?.let { return it }
        val item = catalog?.present(listOf(id))?.firstOrNull()
            ?: throw ApiFailure(HttpStatusCode.NotFound, "ITEM_NOT_FOUND", "No such item")
        return StoredWatchlistEntry(
            UUID.randomUUID().toString(),
            Wire.WatchlistKind.ITEM,
            id,
            item.title,
            null,
            clock.instant().toString(),
        )
    }

    /** The entry for a guide title, or the one the list already holds for it on the same channel or any. */
    private fun titleEntry(request: AddWatchlistEntry, entries: List<StoredWatchlistEntry>): StoredWatchlistEntry {
        val title = request.title?.trim().orEmpty()
        if (title.isEmpty() || title.length > TITLE_LIMIT) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "A title has 1 to $TITLE_LIMIT characters")
        }
        val channel = request.channelId
        if (channel != null && lineup.entry(channel) == null) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Unknown channel")
        }
        entries.firstOrNull {
            it.kind == Wire.WatchlistKind.TITLE && it.title.equals(title, ignoreCase = true) && it.channelId == channel
        }?.let { return it }
        return StoredWatchlistEntry(
            UUID.randomUUID().toString(),
            Wire.WatchlistKind.TITLE,
            null,
            title,
            channel,
            clock.instant().toString(),
        )
    }

    private suspend fun listed(entry: StoredWatchlistEntry): WatchlistEntry {
        val item = entry.itemId?.let { catalog?.present(listOf(it))?.firstOrNull() }
        val next = if (entry.kind ==
            Wire.WatchlistKind.TITLE
        ) {
            nextAirings(listOf(entry), clock.instant())
        } else {
            emptyMap()
        }
        return entry.toWire(item, next[entry.id])
    }

    /** The soonest airing of each title entry that has not ended by [now], within [AIRING_HORIZON], by entry id. */
    private suspend fun nextAirings(entries: List<StoredWatchlistEntry>, now: Instant): Map<String, Program> {
        val anyChannel = entries.any { it.channelId == null }
        val wanted = entries.mapNotNullTo(HashSet()) { it.channelId }
        val titles = entries.mapTo(HashSet()) { it.title.trim().lowercase() }
        val channels = lineup.entries().filter { it.enabled && it.guide != null && (anyChannel || it.id in wanted) }
        val airings = ArrayList<Pair<String, GuideProgram>>()
        for ((guideId, group) in channels.groupBy { it.guide!!.guideId }) {
            val rows = guides.programs(
                guideId,
                group.map {
                    it.guide!!.channelId
                }.distinct(),
                now,
                now + AIRING_HORIZON,
            )
            for (channel in group) {
                rows[channel.guide!!.channelId].orEmpty()
                    .filter { it.endsAt > now && it.title.trim().lowercase() in titles }
                    .forEach { airings += channel.id to it }
            }
        }
        airings.sortBy { it.second.startsAt }
        return entries.mapNotNull { entry ->
            val title = entry.title.trim().lowercase()
            airings.firstOrNull { (channel, program) ->
                program.title.trim().lowercase() == title && (entry.channelId == null || entry.channelId == channel)
            }?.let { (channel, program) -> entry.id to program.toWire(channel) }
        }.toMap()
    }
}

private fun StoredWatchlistEntry.toWire(item: LibraryItem?, next: Program?) =
    WatchlistEntry(id, kind, item?.title ?: title, itemId, channelId, addedAt, item, next)

/**
 * The items [ids] names that the libraries still hold, as the app reads them, in the order given. Music is listened
 * to rather than watched, so the list holds none of it.
 */
private suspend fun LibraryCatalog.present(ids: List<String>): List<LibraryItem> {
    val snapshot = snapshot()
    val states = playStates()
    return ids.mapNotNull { id ->
        snapshot.views[id]?.takeIf { snapshot.shows(it, music = false) }?.out(snapshot, states)
    }
}
