package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.CreateLineupEntry
import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.contract.Channel
import io.ktor.http.HttpStatusCode
import java.util.UUID

/** Builds and checks lineup entries from requests, against the published feeds and the configured guides. */
internal class LineupDrafts(private val feeds: FeedService, private val guides: GuideService) {
    /** A new entry from [request], filled in from its feed channel; its ID must be new to [entries]. */
    fun created(request: CreateLineupEntry, entries: List<LineupEntryRecord>): LineupEntryRecord {
        val candidate = request.channelId?.let {
            feeds.published.channel(it)
                ?: throw ApiFailure(HttpStatusCode.NotFound, "CHANNEL_NOT_FOUND", "Unknown feed channel")
        }
        val id = entryId(request.id)
        if (entries.any { it.id == id }) {
            throw ApiFailure(HttpStatusCode.Conflict, "LINEUP_ENTRY_EXISTS", "Lineup entry already exists")
        }
        return validated(
            LineupEntryRecord(
                id = id,
                number = request.number ?: candidate?.displayNumber ?: invalid("A number is required"),
                name = request.name ?: candidate?.name ?: invalid("A name is required"),
                group = request.group ?: candidate?.tags?.firstOrNull(),
                logo = request.logo ?: candidate?.logo,
                enabled = request.enabled ?: true,
                guide = request.guide ?: candidate?.let(::guideOf),
                offeringIds = request.offerings?.also(::requireKnownOfferings)
                    ?: candidate?.let { feeds.published.offeringsOf(it.id).map { o -> o.id } }.orEmpty(),
            ),
        )
    }

    /** [current] with the fields [request] sets or clears. */
    fun updated(current: LineupEntryRecord, request: UpdateLineupEntry): LineupEntryRecord {
        if (request.gracenoteId != null || request.clearGracenoteId) {
            invalid("A Gracenote station id is kept in the lineup policy; configure one to set it")
        }
        // An enabled flag the household sets is its own, so an automatic disable no longer explains it.
        val household = request.enabled != null
        return validated(
            current.copy(
                number = request.number ?: current.number,
                name = request.name ?: current.name,
                group = request.group.orKept(current.group, request.clearGroup),
                logo = request.logo.orKept(current.logo, request.clearLogo),
                enabled = request.enabled ?: current.enabled,
                guide = request.guide.orKept(current.guide, request.clearGuide),
                offeringIds = request.offerings?.also(::requireKnownOfferings) ?: current.offeringIds,
                autoDisabledAt = current.autoDisabledAt.takeUnless { household },
                autoDisabledReason = current.autoDisabledReason.takeUnless { household },
            ),
        )
    }

    fun validated(entry: LineupEntryRecord): LineupEntryRecord {
        if (!fits(entry.number, NUMBER_LIMIT) || entry.number.any { it.isWhitespace() }) {
            invalid("Number must be 1 to $NUMBER_LIMIT characters without spaces")
        }
        if (!fits(entry.name, NAME_LIMIT)) invalid("Name must be 1 to $NAME_LIMIT characters")
        if (entry.group != null &&
            !fits(entry.group, GROUP_LIMIT)
        ) {
            invalid("Group must be 1 to $GROUP_LIMIT characters")
        }
        val logo = entry.logo?.let { normalizeLogoUrl(it) ?: invalid("Logo must be an HTTP(S) URL") }
        entry.guide?.let { mapping ->
            if (guides.config(mapping.guideId) == null) invalid("Unknown guide ${mapping.guideId}")
            if (!fits(mapping.channelId, NAME_LIMIT)) invalid("Guide channel ID must be 1 to $NAME_LIMIT characters")
        }
        if (entry.offeringIds.size > SOURCES_PER_STATION_LIMIT) {
            invalid("At most $SOURCES_PER_STATION_LIMIT sources per entry")
        }
        return entry.copy(
            name = entry.name.trim(),
            number = entry.number.trim(),
            group = entry.group?.trim(),
            logo = logo,
        )
    }

    private fun entryId(requested: String?): String = requested?.let {
        runCatching { UUID.fromString(it).toString() }.getOrElse { invalid("Entry ID must be a UUID") }
    } ?: UUID.randomUUID().toString()

    /** The first guide that lists the channel's legacy guide ID, if any. */
    private fun guideOf(channel: Channel): GuideMapping? = channel.legacyGuideId?.let { tvgId ->
        guides.guidesListing(tvgId).firstOrNull()?.let { GuideMapping(it, tvgId) }
    }

    private fun requireKnownOfferings(ids: List<String>) {
        if (ids.distinct().size != ids.size) invalid("Duplicate offering IDs")
        ids.firstOrNull { feeds.published.offering(it) == null }
            ?.let { throw ApiFailure(HttpStatusCode.NotFound, "SOURCE_NOT_FOUND", "Unknown source $it") }
    }

    private fun invalid(message: String): Nothing =
        throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", message)

    /** This value when set, else nothing when [clear] says so, else [current]. */
    private fun <T> T?.orKept(current: T?, clear: Boolean): T? = if (clear) null else this ?: current
}
