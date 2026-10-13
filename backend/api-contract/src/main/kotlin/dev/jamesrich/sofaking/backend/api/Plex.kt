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
