package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.AdoptProvider
import dev.jamesrich.sofaking.backend.api.Candidate
import dev.jamesrich.sofaking.backend.api.CandidateOffering
import dev.jamesrich.sofaking.backend.api.CandidatePage
import dev.jamesrich.sofaking.backend.api.CreateLineupEntry
import dev.jamesrich.sofaking.backend.api.LineupEntry
import dev.jamesrich.sofaking.backend.api.LineupOfferingInfo
import dev.jamesrich.sofaking.backend.api.ReorderLineup
import dev.jamesrich.sofaking.backend.api.UpdateLineupEntry
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.store.allHealth
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Clock
import java.time.Instant

/**
 * The admin API under `/api/v1/admin`: the server status, feed candidates, guide channels and the household lineup.
 * JSON only. [revision] is the commit the status reports.
 */
fun Route.adminRoutes(runtime: LiveRuntime, clock: Clock, revision: String = "unknown", hdhrEnabled: Boolean = true) {
    route("/api/v1/admin") {
        get("/status") { call.respond(serverStatus(runtime, clock, revision)) }
        get("/sessions") { call.respond(runtime.sessions.snapshot()) }
        get("/candidates") {
            val params = call.request.queryParameters
            call.respond(
                candidates(
                    runtime,
                    params["q"]?.trim()?.lowercase().orEmpty(),
                    params["provider"],
                    limitOf(params["limit"]),
                    (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0),
                ),
            )
        }
        guideAdminRoutes(runtime)
        lineupRoutes(LineupApi(runtime, clock))
        policyRoutes(runtime.policy)
        plexRoutes(runtime.plex)
        hdhrAccessRoutes(runtime.hdhrAccess, hdhrEnabled)
        setupRoutes(runtime.setup)
        viewerAdminRoutes(runtime)
        tokenAdminRoutes(runtime.tokens)
        libraryAdminRoutes(runtime.libraries)
    }
}

private const val DEFAULT_PAGE = 100
private const val MAX_PAGE = 1000

private fun limitOf(value: String?) = (value?.toIntOrNull() ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)

/** The published channels whose name, number or guide id match [query], a page at a time, with their lineup entries. */
private suspend fun candidates(
    runtime: LiveRuntime,
    query: String,
    provider: String?,
    limit: Int,
    offset: Int,
): CandidatePage {
    val published = runtime.feeds.published
    val states = runtime.store.allHealth()
    val entriesByOffering = HashMap<String, MutableList<String>>()
    for (entry in runtime.lineup.entries()) {
        for (id in entry.offeringIds) entriesByOffering.getOrPut(id) { mutableListOf() } += entry.id
    }
    val matches = published.channels(provider).filter { channel ->
        query.isEmpty() || channel.name.lowercase().contains(query) ||
            channel.displayNumber.lowercase() == query ||
            channel.legacyGuideId?.lowercase()?.contains(query) == true
    }.sortedWith(LineupService.channelOrder)
    val items = matches.drop(offset).take(limit).map { channel ->
        val offerings = published.offeringsOf(channel.id)
        Candidate(
            channel.id,
            published.providerOf(channel.id).orEmpty(),
            channel.displayNumber, channel.name, channel.logo, channel.tags.firstOrNull(),
            channel.legacyGuideId,
            offerings.map {
                CandidateOffering(
                    it.id,
                    it.protocol,
                    states[it.id]?.status?.name ?: "UNVERIFIED",
                    states[it.id]?.manuallyDisabled == true,
                )
            },
            offerings.flatMap { entriesByOffering[it.id].orEmpty() }.distinct(),
        )
    }
    return CandidatePage(matches.size, items)
}

private fun Route.guideAdminRoutes(runtime: LiveRuntime) {
    get("/guides") { call.respond(runtime.guides.statuses) }
    get("/guides/{id}/channels") {
        val id = call.parameters["id"]!!
        if (runtime.guides.config(id) == null) {
            throw ApiFailure(HttpStatusCode.NotFound, "GUIDE_NOT_FOUND", "Unknown guide")
        }
        val params = call.request.queryParameters
        call.respond(runtime.guides.searchChannels(id, params["q"], limitOf(params["limit"])))
    }
}

/** The household lineup as the admin API shows it, and the edits a manual lineup takes. */
private class LineupApi(val runtime: LiveRuntime, private val clock: Clock) {
    val lineup get() = runtime.lineup
    val policy = runtime.policy

    suspend fun info(entry: LineupEntryRecord): LineupEntry {
        val states = runtime.store.allHealth()
        val position = lineup.entries().indexOfFirst { it.id == entry.id }
        return entry.info(runtime, states, position, clock.instant(), gracenoteIds())
    }

    suspend fun all(): List<LineupEntry> {
        val states = runtime.store.allHealth()
        val now = clock.instant()
        val gracenoteIds = gracenoteIds()
        return lineup.entries().mapIndexed { index, entry -> entry.info(runtime, states, index, now, gracenoteIds) }
    }

    private fun gracenoteIds(): Map<String, String> =
        policy?.lastApplied()?.stations.orEmpty().mapNotNull { s -> s.gracenoteId?.let { s.id to it } }.toMap()

    /** Refuses an edit the lineup policy owns while one is configured. */
    fun manual() {
        if (policy != null) throw policyManaged()
    }
}

private fun Route.lineupRoutes(api: LineupApi) {
    val lineup = api.lineup
    val policy = api.policy
    get("/lineup") { call.respond(api.all()) }
    post("/lineup") {
        api.manual()
        call.respond(HttpStatusCode.Created, api.info(lineup.create(call.body<CreateLineupEntry>())))
    }
    put("/lineup/order") {
        api.manual()
        lineup.reorder(call.body<ReorderLineup>().ids)
        call.respond(api.all())
    }
    post("/lineup/adopt") {
        api.manual()
        call.respond(lineup.adopt(call.body<AdoptProvider>()))
    }
    get("/lineup/{id}") {
        val entry = lineup.entry(call.parameters["id"]!!)
            ?: throw ApiFailure(HttpStatusCode.NotFound, "LINEUP_ENTRY_NOT_FOUND", "Unknown lineup entry")
        call.respond(api.info(entry))
    }
    patch("/lineup/{id}") {
        val id = call.parameters["id"]!!
        val request = call.body<UpdateLineupEntry>()
        val updated = if (policy == null) {
            lineup.update(id, request)
        } else {
            policy.edits.updateStation(id, request, call.ifMatch())
                ?: throw ApiFailure(HttpStatusCode.NotFound, "LINEUP_ENTRY_NOT_FOUND", "The station left the lineup")
        }
        call.respond(api.info(updated))
    }
    delete("/lineup/{id}") {
        val id = call.parameters["id"]!!
        if (policy == null) lineup.delete(id) else policy.edits.disableStation(id, call.ifMatch())
        call.respond(HttpStatusCode.NoContent)
    }
}

/** What a route the lineup policy replaces answers while a policy is configured. */
internal fun policyManaged() = ApiFailure(
    HttpStatusCode.Gone,
    "POLICY_MANAGED",
    "The lineup is built from the lineup policy; change it through /api/v1/admin/policy",
)

private fun LineupEntryRecord.info(
    runtime: LiveRuntime,
    states: Map<String, HealthState>,
    position: Int,
    now: Instant,
    gracenoteIds: Map<String, String>,
): LineupEntry {
    val coverage = guide?.let { runtime.guides.coverage[it.guideId]?.get(it.channelId) }
    return LineupEntry(
        id, position, number, name, group, logo, enabled, guide,
        offeringIds.map { id ->
            val offering = runtime.feeds.published.offering(id)
            val state = states[id]
            LineupOfferingInfo(
                id,
                offering?.providerId,
                offering?.let {
                    runtime.feeds.published.channel(it.channelId)?.name
                },
                offering?.protocol,
                if (offering == null) {
                    "WITHDRAWN"
                } else {
                    state?.status?.name ?: "UNVERIFIED"
                },
                state?.manuallyDisabled == true,
            )
        },
        when {
            guide == null -> "unassigned"
            coverage == null -> "empty"
            coverage > now -> "available"
            else -> "stale"
        },
        createdBy, autoDisabledAt?.toString(), autoDisabledReason,
        gracenoteIds[id],
    )
}
