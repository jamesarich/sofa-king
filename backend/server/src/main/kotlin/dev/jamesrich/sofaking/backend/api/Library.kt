package dev.jamesrich.sofaking.backend.api

import kotlinx.serialization.Serializable

/**
 * One media library for `GET /api/v1/admin/libraries`: its folder, how its files are named ([kind] is `movies`,
 * `shows` or `videos`), its present items by kind, and its last scan (null before the first).
 */
@Serializable data class LibraryInfo(
    val id: String,
    val name: String,
    val kind: String,
    val path: String,
    val createdAt: String,
    val items: Map<String, Int> = emptyMap(),
    val scannedAt: String? = null,
    val scanOk: Boolean? = null,
    val scanNote: String? = null,
)

/** `POST /api/v1/admin/libraries`: [path] is a folder inside the media directory. */
@Serializable data class CreateLibrary(val name: String, val kind: String, val path: String)

/** `PATCH /api/v1/admin/libraries/{id}`. */
@Serializable data class UpdateLibrary(val name: String)

/**
 * `POST /api/v1/admin/libraries/{id}/scan`: what the scan found. [unavailable] means the folder was
 * missing or empty, so nothing changed.
 */
@Serializable data class LibraryScanInfo(
    val files: Int,
    val added: Int,
    val updated: Int,
    val missing: Int,
    val removed: Int,
    val unavailable: Boolean = false,
)

/** `GET /api/v1/admin/libraries/folders`: the folders a new library can use. */
@Serializable data class LibraryFolders(val roots: List<String>, val folders: List<String>)

/** A TMDB API read access token or v3 API key for the libraries' details and artwork. */
@Serializable data class SaveTmdbToken(val token: String)

/** A fanart.tv API key for Music libraries' artwork. */
@Serializable data class SaveFanartKey(val key: String)

/** A ListenBrainz user token for artists' most played songs. */
@Serializable data class SaveListenBrainzToken(val token: String)

/** `POST /api/v1/admin/libraries/scan`: a file or folder that changed, as Sonarr or Radarr names it. */
@Serializable data class ScanPath(val path: String)

/** A scan request's answer: the libraries it queued a scan of, none when no library holds the path. */
@Serializable data class ScanQueued(val libraries: List<String>)
