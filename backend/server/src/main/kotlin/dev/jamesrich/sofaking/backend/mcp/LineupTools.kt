package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.admin.LineupFilter
import dev.jamesrich.sofaking.backend.admin.RECENT_PROBES
import dev.jamesrich.sofaking.backend.admin.StationRow
import dev.jamesrich.sofaking.backend.admin.healthWhy
import dev.jamesrich.sofaking.backend.admin.stationRows
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.health.Observation
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.backend.store.recentObservations
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server

// The lineup and the guide channels it maps to: the entry ids the other tools take come from here.

private const val Q = "q"
private const val QUERY_CHARS = 200

private val LINEUP_STATES = mapOf("on" to "enabled", "off" to "hidden", "unverified" to "unverified")
private val LINEUP_GUIDES = mapOf("ok" to "available", "missing" to "missing")

internal fun Server.lineupTools(context: McpContext) {
    val runtime = context.runtime
    tool(
        "lineup",
        "Use to find a channel or see what the lineup holds: entries in lineup order, each with its id (what " +
            "channel, on_later and the recording tools take), number, name, group, whether it is on, guideStatus " +
            "(available, stale, empty or unassigned), sourceCount, why the policy turned it off, and the policy's " +
            "stationKey (what explain_station takes). Filter, then page with offset and limit.",
        schema(
            Q to string("Only entries whose name holds this, whose number it is, or whose station key holds it"),
            "group" to string("Only entries in this group, spelled as the lineup spells it"),
            "state" to
                choice(
                    "on is enabled, off is hidden, unverified is held off until a source qualifies",
                    LINEUP_STATES.keys.toList(),
                ),
            "guide" to
                choice(
                    "ok has a guide that still lists programs, missing is every other guide state",
                    LINEUP_GUIDES.keys.toList(),
                ),
            OFFSET to integer("How many matching entries to skip", 0..MAX_OFFSET, 0),
            LIMIT to integer("How many entries", 1..MAX_PAGE, DEFAULT_PAGE),
        ),
        READ_ONLY,
    ) { arguments ->
        val filter = LineupFilter(
            q = arguments.string(Q)?.take(QUERY_CHARS).orEmpty(),
            group = arguments.string("group")?.take(QUERY_CHARS).orEmpty(),
            state = arguments.choice("state", LINEUP_STATES.keys)?.let { LINEUP_STATES.getValue(it) }.orEmpty(),
            guide = arguments.choice("guide", LINEUP_GUIDES.keys)?.let { LINEUP_GUIDES.getValue(it) }.orEmpty(),
        )
        val (offset, limit) = arguments.page()
        val matching = stationRows(runtime, context.clock).filter(filter::admits)
        LineupPage(
            matching.size,
            offset,
            limit,
            matching.drop(offset).take(limit).map { it.row() },
            nextOffset(offset, limit, matching.size),
        )
    }
    channelTool(context)
    guideChannelsTool(context)
}

private fun Server.channelTool(context: McpContext) {
    tool(
        "channel",
        "Use to see why one channel does not play or has no guide: its entry with every source (sourceId, " +
            "provider, protocol, status, manuallyDisabled, why), its guide mapping and coverage, and its station " +
            "key. Give id, a lineup entry id or a provider channel id (providerChannelId from source_problems; " +
            "then entries that use it are found), or give number.",
        schema(
            "id" to string("A lineup entry id, or a provider channel id"),
            "number" to string("The channel number as the lineup shows it"),
        ),
        READ_ONLY,
    ) { arguments -> channel(context, arguments.string("id"), arguments.string("number")) }
}

private fun Server.guideChannelsTool(context: McpContext) {
    val runtime = context.runtime
    tool(
        "guide_channels",
        "Use to find the guide channel for a lineup entry that has no guide: the channels a guide lists, with " +
            "their ids. Takes a guideId from imports.",
        schema(
            "guideId" to string("The guide's id, from imports"),
            Q to string("Only channels whose id or name holds this"),
            LIMIT to integer("How many channels", 1..MAX_PAGE, DEFAULT_PAGE),
            required = listOf("guideId"),
        ),
        READ_ONLY,
    ) { arguments ->
        val guideId = arguments.string("guideId") ?: invalid("guideId is required; call imports for the guide ids")
        if (runtime.guides.config(guideId) == null) {
            throw ApiFailure(
                HttpStatusCode.NotFound,
                "GUIDE_NOT_FOUND",
                "No guide has that id; call imports for the ids",
            )
        }
        val limit = arguments.int(LIMIT, DEFAULT_PAGE, 1..MAX_PAGE)
        val found = runtime.guides.searchChannels(guideId, arguments.string(Q), limit + 1)
        Listed(found.take(limit).map { GuideChannelRow(it.guideId, it.channelId, it.displayName) }, found.size > limit)
    }
}

private fun StationRow.row() = LineupRow(
    entry.id, entry.number, entry.name, entry.group, entry.enabled, guideStatus, entry.offeringIds.size,
    entry.autoDisabledReason, station?.key,
)

private suspend fun channel(context: McpContext, id: String?, number: String?): ChannelDetail {
    if ((id == null) == (number == null)) invalid("give id or number, one of them; call lineup to find entries")
    val runtime = context.runtime
    val rows = stationRows(runtime, context.clock)
    val (matches, from) = when {
        number != null -> rows.filter { it.entry.number == number } to "number"

        rows.any { it.entry.id == id } -> rows.filter { it.entry.id == id } to "lineupEntry"

        else -> {
            val sources = runtime.feeds.published.offeringsOf(id.orEmpty()).map { it.id }.toSet()
            rows.filter { row -> row.entry.offeringIds.any { it in sources } } to "providerChannel"
        }
    }
    val row = matches.firstOrNull() ?: throw ApiFailure(
        HttpStatusCode.NotFound,
        "CHANNEL_NOT_FOUND",
        "No lineup entry has that number or id, and no entry uses a provider channel with it; call lineup",
    )
    return detail(runtime, row, from, matches.drop(1).map { it.entry.id })
}

private suspend fun detail(runtime: LiveRuntime, row: StationRow, from: String, others: List<String>): ChannelDetail {
    val entry = row.entry
    val health = runtime.store.allHealth()
    val recent = runtime.store.recentObservations(entry.offeringIds, RECENT_PROBES)
    return ChannelDetail(
        id = entry.id,
        number = entry.number,
        name = entry.name,
        group = entry.group,
        enabled = entry.enabled,
        createdBy = entry.createdBy,
        stationKey = row.station?.key,
        disabledReason = row.station?.disabledReason,
        autoDisabledReason = entry.autoDisabledReason,
        guide = guideDetail(runtime, row),
        sources = entry.offeringIds.map { sourceDetail(runtime, it, health[it], recent[it].orEmpty()) },
        resolvedFrom = from,
        otherEntryIds = others,
    )
}

private fun guideDetail(runtime: LiveRuntime, row: StationRow) = row.entry.guide?.let {
    GuideDetail(
        it.guideId,
        it.channelId,
        runtime.guides.channel(it.guideId, it.channelId)?.displayName,
        runtime.guides.coverage[it.guideId]?.get(it.channelId)?.toString(),
        row.guideStatus,
    )
}

private fun sourceDetail(
    runtime: LiveRuntime,
    id: String,
    state: HealthState?,
    recent: List<Observation>,
): SourceDetail {
    val published = runtime.feeds.published
    val offering = published.offering(id)
    val manual = state?.manuallyDisabled == true
    return if (offering == null) {
        SourceDetail(id, status = "WITHDRAWN", manuallyDisabled = manual, why = "The provider no longer lists it")
    } else {
        SourceDetail(
            sourceId = id,
            providerId = offering.providerId,
            providerChannelId = offering.channelId,
            providerChannelName = published.channel(offering.channelId)?.name,
            protocol = offering.protocol,
            status = state?.status?.name ?: HealthStatus.UNVERIFIED.name,
            manuallyDisabled = manual,
            why = healthWhy(state, recent),
        )
    }
}
