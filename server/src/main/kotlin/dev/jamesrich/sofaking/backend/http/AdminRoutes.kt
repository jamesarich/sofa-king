package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.DispatcharrImporter
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.health.HealthState
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.utils.io.readBuffer
import java.time.Instant
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

private val adminJson = Json { encodeDefaults = true }

/** Curation under `/api/v1/admin`: feed candidates, guide channels and the household lineup. JSON only. */
fun Route.adminRoutes(runtime: LiveRuntime) {
    val store = runtime.store
    val importer = DispatcharrImporter(runtime.feeds, runtime.guides, runtime.lineup)
    suspend fun entryInfo(entry: LineupEntryRecord): LineupEntry {
        val states = store.allHealth()
        return entry.info(runtime, states, runtime.lineup.entries().indexOfFirst { it.id == entry.id })
    }
    route("/api/v1/admin") {
        get("/candidates") {
            val query = call.request.queryParameters["q"]?.trim()?.lowercase().orEmpty()
            val provider = call.request.queryParameters["provider"]
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
            val states = store.allHealth()
            val entriesByOffering = HashMap<String, MutableList<String>>()
            for (entry in runtime.lineup.entries()) for (id in entry.offeringIds) entriesByOffering.getOrPut(id) { mutableListOf() } += entry.id
            val matches = runtime.feeds.channels(provider).filter { channel ->
                query.isEmpty() || channel.name.lowercase().contains(query) || channel.displayNumber.lowercase() == query ||
                    channel.legacyGuideId?.lowercase()?.contains(query) == true
            }.sortedWith(LineupService.channelOrder)
            val items = matches.drop(offset).take(limit).map { channel ->
                val offerings = runtime.feeds.offeringsOf(channel.id)
                Candidate(
                    channel.id, runtime.feeds.providerOf(channel.id).orEmpty(), channel.displayNumber, channel.name, channel.logo, channel.tags.firstOrNull(),
                    channel.legacyGuideId,
                    offerings.map { CandidateOffering(it.id, it.protocol, states[it.id]?.status?.name ?: "UNVERIFIED", states[it.id]?.manuallyDisabled == true) },
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
            call.respond(runtime.lineup.entries().mapIndexed { index, entry -> entry.info(runtime, states, index) })
        }
        post("/lineup") { call.respond(HttpStatusCode.Created, entryInfo(runtime.lineup.create(call.body<CreateLineupEntry>()))) }
        put("/lineup/order") {
            runtime.lineup.reorder(call.body<ReorderLineup>().ids)
            val states = store.allHealth()
            call.respond(runtime.lineup.entries().mapIndexed { index, entry -> entry.info(runtime, states, index) })
        }
        post("/lineup/adopt") { call.respond(runtime.lineup.adopt(call.body<AdoptProvider>())) }
        post("/lineup/import") {
            val bytes = call.receiveChannel().readBuffer(IMPORT_LIMIT + 1).readByteArray()
            if (bytes.size > IMPORT_LIMIT) throw ApiFailure(413, "REQUEST_TOO_LARGE", "Import exceeds 16 MiB")
            call.respond(importer.import(adminJson.decodeFromString<ImportLineup>(bytes.toString(Charsets.UTF_8))))
        }
        get("/lineup/{id}") {
            val entry = runtime.lineup.entry(call.parameters["id"]!!) ?: throw ApiFailure(404, "LINEUP_ENTRY_NOT_FOUND", "Unknown lineup entry")
            call.respond(entryInfo(entry))
        }
        patch("/lineup/{id}") {
            val entry = runtime.lineup.update(call.parameters["id"]!!, call.body<UpdateLineupEntry>())
            for (id in entry.offeringIds) runtime.sessions.healthChanged(id)
            call.respond(entryInfo(entry))
        }
        delete("/lineup/{id}") {
            runtime.lineup.delete(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private const val IMPORT_LIMIT = 16 * 1024 * 1024L

private fun LineupEntryRecord.info(runtime: LiveRuntime, states: Map<String, HealthState>, position: Int): LineupEntry {
    val coverage = guide?.let { runtime.guides.coverage()[it.guideId]?.get(it.channelId) }
    return LineupEntry(
        id, position, number, name, group, logo, enabled, guide,
        offeringIds.map { id ->
            val offering = runtime.feeds.offering(id)
            val state = states[id]
            LineupOfferingInfo(
                id, offering?.providerId, offering?.let { runtime.feeds.channel(it.channelId)?.name }, offering?.protocol,
                if (offering == null) "WITHDRAWN" else state?.status?.name ?: "UNVERIFIED", state?.manuallyDisabled == true,
            )
        },
        when {
            guide == null -> "unassigned"
            coverage == null -> "empty"
            coverage > Instant.now() -> "available"
            else -> "stale"
        },
    )
}
