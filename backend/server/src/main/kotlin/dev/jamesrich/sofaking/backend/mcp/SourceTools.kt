package dev.jamesrich.sofaking.backend.mcp

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.admin.healthWhy
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.health.Observation
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.backend.store.recentObservations
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.server.Server

// The providers' imports and the stream sources behind the lineup: where a fetch or a stream fails.

private const val SOURCE_LIMIT = 1000
private const val DEFAULT_SOURCES = 100
private const val SOURCE_PROBES = 10
private const val FAILURES_SHOWN = 20
private const val SOURCE_ID = "sourceId"
private val HEALTH_STATES = HealthStatus.entries.map { it.name }

internal fun Server.sourceTools(context: McpContext) {
    val runtime = context.runtime
    tool(
        "imports",
        "Use when a provider or guide looks empty or stale: each one's last import, with when it last succeeded, " +
            "its error code, and what it brought in. A failing import means the fetch failed; streams that fail " +
            "are source_problems instead. The ids are provider and guide ids.",
        annotations = READ_ONLY,
    ) { Imports(runtime.feeds.statuses, runtime.guides.statuses) }
    tool(
        "source_problems",
        "Use when channels play badly: the stream sources that are not HEALTHY or are disabled by hand, with counts " +
            "by state over every source. Each row has a sourceId (for source_health), the providerChannelId it " +
            "belongs to (a provider's channel, not a lineup entry) and entryIds (the lineup entries that use it, " +
            "for channel). Name a status to list that state instead.",
        schema(
            "status" to choice("Only sources in this state", HEALTH_STATES),
            LIMIT to integer("How many sources to list", 1..SOURCE_LIMIT, DEFAULT_SOURCES),
        ),
        READ_ONLY,
    ) { arguments ->
        val status = arguments.choice("status", HEALTH_STATES)
        val limit = arguments.int(LIMIT, DEFAULT_SOURCES, 1..SOURCE_LIMIT)
        val all = runtime.feeds.sources()
        val problems = all.filter {
            if (status != null) it.status == status else it.status != HealthStatus.HEALTHY.name || it.manuallyDisabled
        }
        val entries = entryIdsBySource(runtime)
        SourceProblems(
            total = all.size,
            count = all.groupingBy { it.status }.eachCount().toSortedMap(),
            manuallyDisabled = all.count { it.manuallyDisabled },
            matching = problems.size,
            sources = problems.take(limit).map {
                SourceRow(
                    it.id,
                    it.channelId,
                    it.providerId,
                    it.status,
                    it.manuallyDisabled,
                    it.lastQualifiedAt,
                    it.nextCheckAt,
                    entries[it.id].orEmpty(),
                )
            },
            truncated = problems.size > limit,
        )
    }
    tool(
        "source_health",
        "Why one stream source is where it is: its state, manual disable, recent failures with their class (the " +
            "source, the provider, auth, rate limit, this server or the tuner), quarantine attempts, when it last " +
            "qualified, when it is checked next, and the lineup entries it serves. Takes a sourceId from " +
            "source_problems or channel. Shows no URLs.",
        schema("sourceId" to string("The source's id, from source_problems or channel"), required = listOf(SOURCE_ID)),
        READ_ONLY,
    ) { arguments -> sourceHealth(runtime, arguments.string(SOURCE_ID) ?: invalid("sourceId is required")) }
}

private suspend fun entryIdsBySource(runtime: LiveRuntime): Map<String, List<String>> =
    runtime.lineup.entries().flatMap { entry -> entry.offeringIds.map { it to entry.id } }
        .groupBy({ it.first }, { it.second })

private suspend fun sourceHealth(runtime: LiveRuntime, id: String): SourceHealthDetail {
    val offering = runtime.feeds.published.offering(id) ?: throw ApiFailure(
        HttpStatusCode.NotFound,
        "SOURCE_NOT_FOUND",
        "No source has that id; call source_problems or channel for source ids",
    )
    val state = runtime.store.allHealth()[id] ?: HealthState()
    val recent = runtime.store.recentObservations(listOf(id), SOURCE_PROBES)[id].orEmpty()
    return SourceHealthDetail(
        sourceId = id,
        providerId = offering.providerId,
        providerChannelId = offering.channelId,
        protocol = offering.protocol,
        status = state.status.name,
        manuallyDisabled = state.manuallyDisabled,
        why = healthWhy(state, recent),
        failures = state.failures.takeLast(FAILURES_SHOWN).map { it.result() },
        recentProbes = recent.map { it.result() },
        quarantineAttempts = state.quarantineAttempts,
        lastQualifiedAt = state.lastQualifiedAt?.toString(),
        lastObservationAt = state.lastObservationAt?.toString(),
        nextCheckAt = state.nextCheckAt?.toString(),
        probationStartedAt = state.probationStartedAt?.toString(),
        entryIds = entryIdsBySource(runtime)[id].orEmpty(),
    )
}

private fun Observation.result() = ProbeResult(observedAt.toString(), qualifiedSuccess, failureClass?.name)
