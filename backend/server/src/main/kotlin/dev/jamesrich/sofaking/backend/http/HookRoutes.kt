package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.MIB
import dev.jamesrich.sofaking.backend.api.MediaUpdates
import dev.jamesrich.sofaking.backend.api.ScanQueued
import dev.jamesrich.sofaking.backend.api.ServarrEvent
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.library.LibraryService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.http.Hooks")

/** The Sonarr and Radarr Webhook connection's events that change files on disk; the others queue nothing. */
private val SERVARR_FILE_EVENTS =
    setOf("Download", "Rename", "EpisodeFileDelete", "MovieFileDelete", "SeriesDelete", "MovieDelete")

/** Where Emby and Jellyfin answer: at the root, and under the prefixes older clients put in front. */
private val MEDIA_BROWSER_PREFIXES = listOf("", "/emby", "/mediabrowser")
private val MEDIA_UPDATED_PATHS = MEDIA_BROWSER_PREFIXES.map { "$it/Library/Media/Updated" }
private val ADMIN_NOTICE_PATHS = MEDIA_BROWSER_PREFIXES.map { "$it/Notifications/Admin" }
private val CONFIGURATION_PATHS = MEDIA_BROWSER_PREFIXES.map { "$it/System/Configuration" }
private val ITEMS_PATHS = MEDIA_BROWSER_PREFIXES.map { "$it/Items" }

/**
 * The Emby-style routes Sonarr's and Radarr's Emby / Jellyfin connection calls, outside `/api`: a hook token opens them,
 * sent as `X-MediaBrowser-Token` as that connection sends it.
 */
internal val MEDIA_BROWSER_HOOK_PATHS =
    (MEDIA_UPDATED_PATHS + ADMIN_NOTICE_PATHS + CONFIGURATION_PATHS + ITEMS_PATHS).toSet()

/** Whether [path] is an inbound hook: under [HOOKS_PATH], or one of [MEDIA_BROWSER_HOOK_PATHS]. */
internal fun isHookPath(path: String): Boolean =
    path == HOOKS_PATH || path.startsWith("$HOOKS_PATH/") || path in MEDIA_BROWSER_HOOK_PATHS

/** A Sonarr Download event with a season pack's episodes and release runs to tens of kilobytes. */
internal const val HOOK_BODY_LIMIT = MIB

/**
 * The hooks that tell the libraries a file changed: Sonarr's and Radarr's Webhook connection at
 * `/api/v1/hooks/servarr`, and their Emby / Jellyfin connection at `/Library/Media/Updated`, with the reads that
 * connection makes first. Each refresh queues a scan of the library holding the path, and answers 202 with the
 * libraries it queued, none when no library holds it.
 */
internal fun Route.hookRoutes(libraries: LibraryService?) {
    fun loaded(): LibraryService = libraries
        ?: throw ApiFailure(
            HttpStatusCode.NotFound,
            "LIBRARIES_NOT_AVAILABLE",
            "Media libraries are not available on this server",
        )
    post("$HOOKS_PATH/servarr") {
        val service = loaded()
        val event = call.appBody<ServarrEvent>()
        val folder = event.series?.path ?: event.movie?.folderPath ?: event.movie?.path
        val queued = folder?.takeIf { event.eventType in SERVARR_FILE_EVENTS }?.let { service.requestScanAt(it) }
        log.info("library hook from=servarr libraries={}", queued?.size ?: 0)
        call.respond(HttpStatusCode.Accepted, ScanQueued(queued.orEmpty().map { it.id }))
    }
    for (path in MEDIA_UPDATED_PATHS) {
        post(path) {
            val service = loaded()
            val updates = call.appBody<MediaUpdates>().updates
            val queued = updates.mapNotNull { it.path }.distinct().flatMap { service.requestScanAt(it) }
                .map { it.id }.distinct()
            log.info("library hook from=media-browser updates={} libraries={}", updates.size, queued.size)
            call.respond(HttpStatusCode.Accepted, ScanQueued(queued))
        }
    }
    // The connection's "Send Notifications" option posts here; there is nobody to notify.
    for (path in ADMIN_NOTICE_PATHS) {
        post(path) { call.respond(HttpStatusCode.NoContent) }
    }
    // The connection's Test button reads the configuration, and only checks that the token opens it.
    for (path in CONFIGURATION_PATHS) {
        get(path) { call.respondText("{}", ContentType.Application.Json) }
    }
    // Before a refresh the connection asks for the series or movie by id, to refresh the folders the server knows it
    // by; with none, it sends the folder it knows itself.
    for (path in ITEMS_PATHS) {
        get(path) { call.respondText("""{"Items":[],"TotalRecordCount":0}""", ContentType.Application.Json) }
    }
}
