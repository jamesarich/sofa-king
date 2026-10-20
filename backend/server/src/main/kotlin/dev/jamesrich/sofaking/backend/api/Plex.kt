package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

/** The last Plex sync: when it ran and why, the DVRs whose guide it reloaded, each device it mapped, and what it could not do. */
@Serializable data class PlexStatus(
    val syncMinutes: Int,
    val lastSyncAt: String? = null,
    val lastCause: String? = null,
    val lastError: String? = null,
    val dvrs: List<String> = emptyList(),
    val devices: List<PlexDeviceStatus> = emptyList(),
    /** DeviceIDs this server serves that no Plex DVR holds. */
    val missingDevices: List<String> = emptyList(),
    val findings: List<String> = emptyList(),
)

/** One of this server's HDHomeRun devices in a Plex DVR: [device] is its index, [plexKey] Plex's key for it, [mapped] the channels saved. */
@Serializable data class PlexDeviceStatus(
    val deviceId: String,
    val device: Int,
    val plexKey: String,
    val dvr: String,
    val mapped: Int,
    val added: Boolean = false,
)

/**
 * Who may reach the HDHomeRun emulation: [configured] from `SOFA_HDHR_ALLOW`, [allowed] from the portal, and the
 * addresses it refused since the start that are still refused, newest first. [open] is true when every address is admitted.
 */
@Serializable data class HdhrAccessStatus(
    val enabled: Boolean,
    val open: Boolean,
    val configured: List<String>,
    val allowed: List<String>,
    val refused: List<HdhrRefusedClient> = emptyList(),
)

/** A client the emulation refused: its address, when it last asked and the path it asked for. */
@Serializable data class HdhrRefusedClient(val address: String, val lastAt: String, val path: String)

/** The addresses the portal allows, IP literals or CIDR blocks; they replace the saved list. */
@Serializable data class UpdateHdhrAccess(val allowed: List<String>)
