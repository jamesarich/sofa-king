package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.library.LibraryService
import dev.jamesrich.sofaking.contract.UpdatePlayState
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/** The libraries as the app reads them, under `/api/v1/libraries` and `/api/v1/library`. */
fun Route.libraryRoutes(libraries: LibraryService) {
    val catalog = libraries.catalog
    get("/api/v1/libraries") { call.respond(catalog.libraries()) }
    get("/api/v1/libraries/{id}/items") {
        val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..500 } ?: throw ApiFailure(400, "INVALID_REQUEST", "limit is 1 to 500") } ?: 100
        val unwatched = call.request.queryParameters["unwatched"] == "true"
        call.respond(catalog.items(call.parameters["id"]!!, call.request.queryParameters["sort"], unwatched, call.request.queryParameters["cursor"], limit))
    }
    get("/api/v1/library/home") { call.respond(catalog.home()) }
    get("/api/v1/library/search") {
        val query = call.request.queryParameters["q"]?.takeIf { it.length <= 100 } ?: throw ApiFailure(400, "INVALID_REQUEST", "q is 1 to 100 characters")
        call.respond(catalog.search(query, 50))
    }
    get("/api/v1/library/items/{id}") { call.respond(catalog.item(call.parameters["id"]!!)) }
    put("/api/v1/library/items/{id}/play-state") { call.respond(catalog.updatePlayState(call.parameters["id"]!!, call.appBody<UpdatePlayState>())) }
}

/**
 * Posters and backdrops under `/artwork/{item}/{poster|backdrop}`. Image loaders fetch these with no credentials,
 * like logos, so the route takes none; it serves only the image the library holds for that item.
 */
fun Route.artworkRoutes(libraries: LibraryService) {
    get("/artwork/{id}/{role}") {
        val artwork = libraries.artwork ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND", "Unknown artwork")
        val view = libraries.catalog.snapshot().views[call.parameters["id"]!!] ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND", "Unknown artwork")
        val source = when (call.parameters["role"]) {
            "poster" -> view.poster
            "backdrop" -> view.backdrop
            else -> null
        } ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND", "Unknown artwork")
        val etag = "\"${source.version}\""
        call.response.header(HttpHeaders.ETag, etag)
        // The URL carries the version, so a changed image gets a new URL and the old one may be kept.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=604800")
        call.response.header("X-Content-Type-Options", "nosniff")
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) return@get call.respond(HttpStatusCode.NotModified)
        val image = artwork.image(source) ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND", "Unknown artwork")
        call.respond(LocalPathContent(image.file, ContentType.parse(image.contentType)))
    }
}
