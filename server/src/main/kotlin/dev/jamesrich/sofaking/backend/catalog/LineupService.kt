package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.lineup
import dev.jamesrich.sofaking.backend.store.saveLineup
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        val revision = hash(entries.joinToString("\n") { "${it.id}|${it.number}|${it.name}|${it.group}|${it.logo}|${it.enabled}|${it.guide}|${it.offeringIds}" })
        val wanted: Map<String, Set<String>> = entries.mapNotNull { it.guide }.groupBy({ it.guideId }, { it.channelId }).mapValues { it.value.toSet() }
    }

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
        val entry = draft(request, entries)
        val position = request.position?.coerceIn(0, entries.size) ?: entries.size
        entries.add(position, entry)
        entry
    }

    suspend fun update(id: String, request: UpdateLineupEntry): LineupEntryRecord = mutate { entries ->
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "Unknown lineup entry")
        val current = entries[index]
        for (field in request.pin + request.unpin) if (field !in PINNABLE_FIELDS) throw ApiFailure(400, "INVALID_REQUEST", "Unknown pin $field")
        val touched = buildSet {
            if (request.number != null) add("number")
            if (request.name != null) add("name")
            if (request.group != null || request.clearGroup) add("group")
            if (request.logo != null || request.clearLogo) add("logo")
            if (request.enabled != null) add("enabled")
            if (request.offerings != null) add("offerings")
            if (request.guide != null || request.clearGuide) add("guide")
        }
        // The household now owns the enabled flag, whichever way it set it; automation stops tracking its own disable.
        val household = request.enabled != null
        val next = validated(
            current.copy(
                number = request.number ?: current.number,
                name = request.name ?: current.name,
                group = if (request.clearGroup) null else request.group ?: current.group,
                logo = if (request.clearLogo) null else request.logo ?: current.logo,
                enabled = request.enabled ?: current.enabled,
                guide = if (request.clearGuide) null else request.guide ?: current.guide,
                offeringIds = request.offerings?.also(::requireKnownOfferings) ?: current.offeringIds,
                pinned = current.pinned + touched + request.pin - request.unpin.toSet(),
                autoDisabledAt = if (household) null else current.autoDisabledAt,
                autoDisabledReason = if (household) null else current.autoDisabledReason,
            ),
        )
        entries.removeAt(index)
        entries.add(request.position?.coerceIn(0, entries.size) ?: index, next)
        next
    }

    suspend fun delete(id: String) = mutate { entries ->
        if (!entries.removeIf { it.id == id }) throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "Unknown lineup entry")
    }

    /** [ids] must name every entry exactly once. */
    suspend fun reorder(ids: List<String>) = mutate { entries ->
        val byId = entries.associateBy { it.id }
        if (ids.size != entries.size || ids.toSet() != byId.keys) throw ApiFailure(400, "INVALID_REQUEST", "Order must list every lineup entry exactly once")
        entries.clear()
        ids.mapTo(entries) { byId.getValue(it) }
    }

    /** Adds every feed channel of [providerId] whose sources no entry references yet, in channel-number order. */
    suspend fun adopt(request: AdoptProvider): AdoptResult = mutate { entries ->
        if (feeds.configs.none { it.id == request.providerId }) throw ApiFailure(404, "PROVIDER_NOT_FOUND", "Unknown provider")
        val referenced = entries.flatMapTo(HashSet()) { it.offeringIds }
        var added = 0
        var skipped = 0
        for (channel in feeds.channels(request.providerId).sortedWith(channelOrder)) {
            val offerings = feeds.offeringsOf(channel.id)
            if (offerings.isEmpty() || offerings.any { it.id in referenced }) {
                skipped++
                continue
            }
            entries += draft(CreateLineupEntry(channelId = channel.id, enabled = request.enabled), entries)
            added++
        }
        AdoptResult(added, skipped)
    }

    /** Applies the entries [block] produces; the import matcher decides what they are. */
    internal suspend fun <R> mutate(block: (MutableList<LineupEntryRecord>) -> R): R = mutex.withLock {
        val entries = published.get().entries.toMutableList()
        val result = block(entries)
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate lineup entry IDs" }
        store.saveLineup(entries, clock.instant())
        val before = published.get().wanted
        val next = Published(entries)
        published.set(next)
        for ((guideId, channels) in next.wanted) if (!before[guideId].orEmpty().containsAll(channels)) guides.requestRefresh(guideId)
        onChanged()
        result
    }

    private fun draft(request: CreateLineupEntry, entries: List<LineupEntryRecord>): LineupEntryRecord {
        val candidate = request.channelId?.let { feeds.channel(it) ?: throw ApiFailure(404, "CHANNEL_NOT_FOUND", "Unknown feed channel") }
        val id = request.id?.let { runCatching { UUID.fromString(it).toString() }.getOrElse { throw ApiFailure(400, "INVALID_REQUEST", "Entry ID must be a UUID") } }
            ?: UUID.randomUUID().toString()
        if (entries.any { it.id == id }) throw ApiFailure(409, "LINEUP_ENTRY_EXISTS", "Lineup entry already exists")
        val offerings = request.offerings?.also(::requireKnownOfferings) ?: candidate?.let { feeds.offeringsOf(it.id).map { o -> o.id } }.orEmpty()
        val guide = request.guide ?: candidate?.legacyGuideId?.let { tvgId -> guides.guidesListing(tvgId).firstOrNull()?.let { GuideMapping(it, tvgId) } }
        val pinned = buildSet {
            if (request.number != null) add("number")
            if (request.name != null) add("name")
            if (request.group != null) add("group")
            if (request.logo != null) add("logo")
            if (request.enabled != null) add("enabled")
            if (request.offerings != null) add("offerings")
            if (request.guide != null) add("guide")
        }
        return validated(
            LineupEntryRecord(
                id = id,
                number = request.number ?: candidate?.displayNumber ?: throw ApiFailure(400, "INVALID_REQUEST", "A number is required"),
                name = request.name ?: candidate?.name ?: throw ApiFailure(400, "INVALID_REQUEST", "A name is required"),
                group = request.group ?: candidate?.tags?.firstOrNull(),
                logo = request.logo ?: candidate?.logo,
                enabled = request.enabled ?: true,
                guide = guide,
                offeringIds = offerings,
                pinned = pinned,
            ),
        )
    }

    private fun requireKnownOfferings(ids: List<String>) {
        if (ids.distinct().size != ids.size) throw ApiFailure(400, "INVALID_REQUEST", "Duplicate offering IDs")
        ids.firstOrNull { feeds.offering(it) == null }?.let { throw ApiFailure(404, "SOURCE_NOT_FOUND", "Unknown source $it") }
    }

    internal fun validated(entry: LineupEntryRecord): LineupEntryRecord {
        fun invalid(message: String): Nothing = throw ApiFailure(400, "INVALID_REQUEST", message)
        if (entry.number.isBlank() || entry.number.length > 32 || entry.number.any { it.isWhitespace() }) invalid("Number must be 1 to 32 characters without spaces")
        if (entry.name.isBlank() || entry.name.length > 256) invalid("Name must be 1 to 256 characters")
        if (entry.group != null && (entry.group.isBlank() || entry.group.length > 128)) invalid("Group must be 1 to 128 characters")
        val logo = entry.logo?.let { normalizeLogoUrl(it) ?: invalid("Logo must be an HTTP(S) URL") }
        entry.guide?.let { mapping ->
            if (guides.config(mapping.guideId) == null) invalid("Unknown guide ${mapping.guideId}")
            if (mapping.channelId.isBlank() || mapping.channelId.length > 256) invalid("Guide channel ID must be 1 to 256 characters")
        }
        if (entry.offeringIds.size > 64) invalid("At most 64 sources per entry")
        return entry.copy(name = entry.name.trim(), number = entry.number.trim(), group = entry.group?.trim(), logo = logo)
    }

    companion object {
        val channelOrder = compareBy<Channel>({ it.displayNumber.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.name }, { it.id })
    }
}
