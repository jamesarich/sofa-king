package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.api.ServerStatus
import dev.jamesrich.sofaking.backend.api.StatusChannels
import dev.jamesrich.sofaking.backend.api.StatusImportFailure
import dev.jamesrich.sofaking.backend.api.StatusImports
import dev.jamesrich.sofaking.backend.api.StatusPlex
import dev.jamesrich.sofaking.backend.api.StatusPolicy
import dev.jamesrich.sofaking.backend.api.StatusSources

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The summary `GET /api/v1/admin/status` answers, built from what the services hold in memory and two small queries. */
internal suspend fun serverStatus(runtime: LiveRuntime, clock: Clock, revision: String): ServerStatus {
    val now = clock.instant()
    val entries = runtime.lineup.entries()
    val sources = runtime.feeds.sources()
    val policy = runtime.policy
    val plex = runtime.plex?.status()
    return ServerStatus(
        revision = revision,
        startedAt = runtime.startedAt.toString(),
        uptimeSeconds = Duration.between(runtime.startedAt, now).seconds.coerceAtLeast(0),
        schemaVersion = runtime.store.schemaVersion(),
        channels = StatusChannels(entries.size, entries.count { it.enabled }, runtime.catalog.catalog().channels.count { it.playbackEligible }),
        sources = StatusSources(sources.size, sources.groupingBy { it.status }.eachCount().toSortedMap(), sources.count { it.manuallyDisabled }),
        providers = imports(runtime.feeds.providers().map { Import(it.id, it.lastSuccessAt, it.errorCode) }),
        guides = imports(runtime.guides.statuses().map { Import(it.id, it.lastSuccessAt, it.errorCode) }),
        policy = policy?.let { service ->
            val last = service.lastApplied()?.result
            StatusPolicy(service.document().revision, last?.appliedAt, last?.changed)
        },
        plex = plex?.let { StatusPlex(it.lastSyncAt, it.devices.sumOf { device -> device.mapped }, it.lastError) },
        activeSessions = runtime.sessions.snapshot().size,
    )
}

private class Import(val id: String, val lastSuccessAt: String?, val errorCode: String?)

private fun imports(all: List<Import>): StatusImports = StatusImports(
    total = all.size,
    pending = all.count { it.lastSuccessAt == null && it.errorCode == null },
    lastImportAt = all.mapNotNull { it.lastSuccessAt?.let { at -> runCatching { Instant.parse(at) }.getOrNull() } }.maxOrNull()?.toString(),
    failing = all.filter { it.errorCode != null }.sortedBy { it.id }.map { StatusImportFailure(it.id, it.errorCode!!, it.lastSuccessAt) },
)
