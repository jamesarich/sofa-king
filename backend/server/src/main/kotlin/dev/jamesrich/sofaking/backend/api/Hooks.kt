package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The fields `POST /api/v1/hooks/servarr` reads from the Webhook connection of Sonarr or Radarr; the rest of the
 * payload is ignored.
 */
@Serializable data class ServarrEvent(
    val eventType: String = "",
    val series: ServarrFolder? = null,
    val movie: ServarrFolder? = null,
    val episodeFile: ServarrFile? = null,
    val movieFile: ServarrFile? = null,
)

/** A series (`path`) or a movie (`folderPath`) as Sonarr or Radarr names its folder. */
@Serializable data class ServarrFolder(val path: String? = null, val folderPath: String? = null)

/** A file, relative to its series or movie folder. */
@Serializable data class ServarrFile(val relativePath: String? = null)

/** `POST /Library/Media/Updated`, as Sonarr's and Radarr's Emby / Jellyfin connection sends it. */
@Serializable data class MediaUpdates(@SerialName("Updates") val updates: List<MediaUpdate> = emptyList())

/** One changed file or folder: `UpdateType` is `Created`, `Modified` or `Deleted`. */
@Serializable data class MediaUpdate(
    @SerialName("Path") val path: String? = null,
    @SerialName("UpdateType") val updateType: String? = null,
)
