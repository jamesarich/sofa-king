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
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.query
import io.ktor.server.routing.route
import java.time.Clock
import java.time.Instant

/**
 * The admin API under `/api/v1/admin`: the server status, feed candidates, guide channels and the household lineup.
 * JSON only. [revision] is the commit the status reports.
 */
fun Route.adminRoutes(runtime: LiveRuntime, clock: Clock, revision: String = "unknown", hdhrEnabled: Boolean = true) {
    val store = runtime.store
    suspend fun entryInfo(entry: LineupEntryRecord): LineupEntry {
        val states = store.allHealth()
        return entry.info(runtime, states, runtime.lineup.entries().indexOfFirst { it.id == entry.id }, clock.instant())
    }
    val policy = runtime.policy
    fun manual() {
        if (policy != null) throw policyManaged()
    }
    route("/api/v1/admin") {
        get("/status") { call.respond(serverStatus(runtime, clock, revision)) }
        get("/sessions") { call.respond(runtime.sessions.snapshot()) }
        get("/candidates") {
            val query = call.request.queryParameters["q"]?.trim()?.lowercase().orEmpty()
            val provider = call.request.queryParameters["provider"]
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
            val states = store.allHealth()
            val entriesByOffering = HashMap<String, MutableList<String>>()
            for (entry in runtime.lineup.entries()) {
                for (id in entry.offeringIds) {
                    entriesByOffering.getOrPut(id) { mutableListOf() } +=
                        entry.id
                }
            }
            val matches = runtime.feeds.channels(provider).filter { channel ->
                query.isEmpty() || channel.name.lowercase().contains(query) ||
                    channel.displayNumber.lowercase() == query ||
                    channel.legacyGuideId?.lowercase()?.contains(query) == true
            }.sortedWith(LineupService.channelOrder)
            val items = matches.drop(offset).take(limit).map { channel ->
                val offerings = runtime.feeds.offeringsOf(channel.id)
                Candidate(
                    channel.id,
                    runtime.feeds.providerOf(
                        channel.id,
                    ).orEmpty(),
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
            call.respond(CandidatePage(matches.size, items))
        }
        get("/guides") { call.respond(runtime.guides.statuses()) }
        get("/guides/{id}/channels") {
            val id = call.parameters["id"]!!
            if (runtime.guides.config(id) == null) throw ApiFailure(404, "GUIDE_NOT_FOUND", "Unknown guide")
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
            call.respond(runtime.guides.searchChannels(id, call.request.queryParameters["q"], limit))
        }
        get("/lineup") {
            val states = store.allHealth()
            val now = clock.instant()
            call.respond(
                runtime.lineup.entries().mapIndexed { index, entry ->
                    entry.info(runtime, states, index, now)
                },
            )
        }
        post("/lineup") {
            manual()
            call.respond(HttpStatusCode.Created, entryInfo(runtime.lineup.create(call.body<CreateLineupEntry>())))
        }
        put("/lineup/order") {
            manual()
            runtime.lineup.reorder(call.body<ReorderLineup>().ids)
            val states = store.allHealth()
            val now = clock.instant()
            call.respond(
                runtime.lineup.entries().mapIndexed { index, entry ->
                    entry.info(runtime, states, index, now)
                },
            )
        }
        post("/lineup/adopt") {
            manual()
            call.respond(runtime.lineup.adopt(call.body<AdoptProvider>()))
        }
        get("/lineup/{id}") {
            val entry =
                runtime.lineup.entry(call.parameters["id"]!!)
                    ?: throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "Unknown lineup entry")
            call.respond(entryInfo(entry))
        }
        patch("/lineup/{id}") {
            val id = call.parameters["id"]!!
            val request = call.body<UpdateLineupEntry>()
            if (policy == null) {
                call.respond(entryInfo(runtime.lineup.update(id, request)))
            } else {
                call.respond(
                    entryInfo(
                        policy.updateStation(id, request, call.ifMatch())
                            ?: throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "The station left the lineup"),
                    ),
                )
            }
        }
        delete("/lineup/{id}") {
            val id = call.parameters["id"]!!
            if (policy == null) runtime.lineup.delete(id) else policy.disableStation(id, call.ifMatch())
            call.respond(HttpStatusCode.NoContent)
        }
        policyRoutes(runtime.policy)
        plexRoutes(runtime.plex)
        hdhrAccessRoutes(runtime.hdhrAccess, hdhrEnabled)
        setupRoutes(runtime.setup)
        viewerAdminRoutes(runtime)
        libraryAdminRoutes(runtime.libraries)
    }
}

/** What a route the lineup policy replaces answers while a policy is configured. */
internal fun policyManaged() = ApiFailure(
    410,
    "POLICY_MANAGED",
    "The lineup is built from the lineup policy; change it through /api/v1/admin/policy",
)

private fun LineupEntryRecord.info(
    runtime: LiveRuntime,
    states: Map<String, HealthState>,
    position: Int,
    now: Instant,
): LineupEntry {
    val coverage = guide?.let { runtime.guides.coverage()[it.guideId]?.get(it.channelId) }
    return LineupEntry(
        id, position, number, name, group, logo, enabled, guide,
        offeringIds.map { id ->
            val offering = runtime.feeds.offering(id)
            val state = states[id]
            LineupOfferingInfo(
                id,
                offering?.providerId,
                offering?.let {
                    runtime.feeds.channel(it.channelId)?.name
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
    )
}
