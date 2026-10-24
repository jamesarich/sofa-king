package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.logos.LogoCache
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Cached lineup logos under `/logos/{key}`. Players and Plex fetch these with no credentials, so the route takes
 * none; the key is a hash of a URL the lineup names, so nothing else is fetched or served through it.
 */
fun Route.logoRoutes(logos: LogoCache) {
    get("/logos/{key}") {
        val key =
            call.parameters["key"]?.takeIf { LogoCache.KEY.matches(it) }
                ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown logo")
        val record =
            logos.lookup(key) ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown logo")
        val etag = "\"${record.contentHash}\""
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.response.header("X-Content-Type-Options", "nosniff")
        // An SVG is XML a browser could run scripts from if it opened the URL itself; the policy
        // forbids that and keeps rendering.
        call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) return@get call.respond(HttpStatusCode.NotModified)
        // Streamed from the cache file, with its length and modification time; a HEAD or a range costs no read here.
        val file = logos.file(record) ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown logo")
        call.respond(LocalPathContent(file, ContentType.parse(record.contentType!!)))
    }
}
