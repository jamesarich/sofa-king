package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.AdoptProvider
import dev.jamesrich.sofaking.backend.api.AdoptResult
import dev.jamesrich.sofaking.backend.api.CreateLineupEntry
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.lineup
import dev.jamesrich.sofaking.backend.store.saveLineup
import dev.jamesrich.sofaking.contract.Channel
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/**
 * The household lineup: what the app sees, in order. Entries survive feed imports and refer to
 * offerings by id, so a withdrawn source leaves a gap rather than deleting the channel.
 */
class LineupService(
    private val store: BackendStore,
    private val clock: Clock,
    private val feeds: FeedService,
    private val guides: GuideService,
    /** Runs after each change, once the new lineup is published. */
    private val onChanged: () -> Unit = {},
) {
    private class Published(val entries: List<LineupEntryRecord>) {
        val byId = entries.associateBy { it.id }
        val revision =
            hash(
                entries.joinToString("\n") {
                    "${it.id}|${it.number}|${it.name}|${it.group}|${it.logo}|${it.enabled}|${it.guide}|" +
                        "${it.offeringIds}"
                },
            )
        val wanted: Map<String, Set<String>> = entries.mapNotNull {
            it.guide
        }.groupBy({ it.guideId }, { it.channelId }).mapValues { it.value.toSet() }
    }

    /** Builds and checks the entries this service writes; the policy run checks its own with it too. */
    internal val drafts = LineupDrafts(feeds, guides)

    private val mutex = Mutex()
    private val published = AtomicReference(Published(emptyList()))

    suspend fun initialize() {
        published.set(Published(store.lineup()))
    }

    fun entries(): List<LineupEntryRecord> = published.get().entries

    fun entry(id: String): LineupEntryRecord? = published.get().byId[id]

    fun revision(): String = published.get().revision

    /** Guide id to the XMLTV channel ids the lineup maps onto it. */
    fun wanted(): Map<String, Set<String>> = published.get().wanted

    suspend fun create(request: CreateLineupEntry): LineupEntryRecord = mutate { entries ->
        val entry = drafts.created(request, entries)
        val position = request.position?.coerceIn(0, entries.size) ?: entries.size
        entries.add(position, entry)
        entry
    }

    suspend fun update(id: String, request: UpdateLineupEntry): LineupEntryRecord = mutate { entries ->
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) throw ApiFailure(HttpStatusCode.NotFound, "LINEUP_ENTRY_NOT_FOUND", "Unknown lineup entry")
        val next = drafts.updated(entries[index], request)
        entries.removeAt(index)
        entries.add(request.position?.coerceIn(0, entries.size) ?: index, next)
        next
    }

    suspend fun delete(id: String) = mutate { entries ->
        if (!entries.removeIf { it.id == id }) {
            throw ApiFailure(HttpStatusCode.NotFound, "LINEUP_ENTRY_NOT_FOUND", "Unknown lineup entry")
        }
    }

    /** [ids] must name every entry exactly once. */
    suspend fun reorder(ids: List<String>) = mutate { entries ->
        val byId = entries.associateBy { it.id }
        if (ids.size != entries.size || ids.toSet() != byId.keys) {
            throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_REQUEST",
                "Order must list every lineup entry exactly once",
            )
        }
        entries.clear()
        ids.mapTo(entries) { byId.getValue(it) }
    }

    /** Adds every feed channel of [providerId] whose sources no entry references yet, in channel-number order. */
    suspend fun adopt(request: AdoptProvider): AdoptResult = mutate { entries ->
        if (feeds.configs.none { it.id == request.providerId }) {
            throw ApiFailure(HttpStatusCode.NotFound, "PROVIDER_NOT_FOUND", "Unknown provider")
        }
        val referenced = entries.flatMapTo(HashSet()) { it.offeringIds }
        var added = 0
        var skipped = 0
        for (channel in feeds.published.channels(request.providerId).sortedWith(channelOrder)) {
            val offerings = feeds.published.offeringsOf(channel.id)
            if (offerings.isEmpty() || offerings.any { it.id in referenced }) {
                skipped++
                continue
            }
            entries += drafts.created(CreateLineupEntry(channelId = channel.id, enabled = request.enabled), entries)
            added++
        }
        AdoptResult(added, skipped)
    }

    /** Writes and publishes the entries [block] leaves, and refreshes any guide that gains a mapped channel. */
    internal suspend fun <R> mutate(block: (MutableList<LineupEntryRecord>) -> R): R = mutex.withLock {
        val entries = published.get().entries.toMutableList()
        val result = block(entries)
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate lineup entry IDs" }
        store.saveLineup(entries, clock.instant())
        val before = published.get().wanted
        val next = Published(entries)
        published.set(next)
        for ((guideId, channels) in next.wanted) {
            if (!before[guideId].orEmpty().containsAll(channels)) {
                guides.requestRefresh(guideId)
            }
        }
        onChanged()
        result
    }

    companion object {
        val channelOrder =
            compareBy<Channel>({ it.displayNumber.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.name }, { it.id })
    }
}
