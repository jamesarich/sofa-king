package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

/**
 * One summary of the server for `GET /api/v1/admin/status`. [revision] is the commit the image was built from, or
 * `unknown`; [policy] is null without a lineup policy and [plex] without Plex sync.
 */
@Serializable data class ServerStatus(
    val revision: String,
    val startedAt: String,
    val uptimeSeconds: Long,
    val schemaVersion: Int,
    val channels: StatusChannels,
    val sources: StatusSources,
    val providers: StatusImports,
    val guides: StatusImports,
    val policy: StatusPolicy? = null,
    val plex: StatusPlex? = null,
    val activeSessions: Int = 0,
)

/** Lineup entries: all of them, those turned on, and those on with a source eligible for playback. */
@Serializable data class StatusChannels(val total: Int, val enabled: Int, val eligible: Int)

/** Feed sources by health status (`HEALTHY`, `UNVERIFIED` and so on), and how many are disabled by hand. */
@Serializable
data class StatusSources(val total: Int, val byStatus: Map<String, Int> = emptyMap(), val manuallyDisabled: Int = 0)

/**
 * Providers or guides: how many are configured, how many are waiting for their first import, the newest successful
 * import, and each one whose last import failed.
 */
@Serializable data class StatusImports(
    val total: Int,
    val pending: Int = 0,
    val lastImportAt: String? = null,
    val failing: List<StatusImportFailure> = emptyList(),
)

@Serializable data class StatusImportFailure(val id: String, val errorCode: String, val lastSuccessAt: String? = null)

/** The loaded policy's [revision] and the last apply that built the lineup; [lastRunAt] is null until one has. */
@Serializable
data class StatusPolicy(val revision: String, val lastRunAt: String? = null, val lastRunChanged: Boolean? = null)

/** The last Plex sync: when, the channels it mapped across all devices, and its error. */
@Serializable data class StatusPlex(val lastSyncAt: String? = null, val mapped: Int = 0, val lastError: String? = null)
