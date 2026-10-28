package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.library.IMDB_ID
import dev.jamesrich.sofaking.backend.library.LibraryLyrics
import dev.jamesrich.sofaking.backend.library.LibraryService
import dev.jamesrich.sofaking.backend.library.ProviderIds
import dev.jamesrich.sofaking.backend.library.RADIO_SIZE
import dev.jamesrich.sofaking.backend.library.VodSessions
import dev.jamesrich.sofaking.backend.library.libraryRadio
import dev.jamesrich.sofaking.backend.library.radio
import dev.jamesrich.sofaking.contract.OpenVodPlayback
import dev.jamesrich.sofaking.contract.UpdatePlayState
import dev.jamesrich.sofaking.contract.UpdateVodPlayback
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** The libraries as the app reads them, under `/api/v1/libraries` and `/api/v1/library`. */
fun Route.libraryRoutes(libraries: LibraryService) {
    val catalog = libraries.catalog
    get("/api/v1/libraries") { call.respond(catalog.libraries(call.showsMusic())) }
    get("/api/v1/libraries/{id}/items") {
        val limit =
            call.request.queryParameters["limit"]?.let {
                it.toIntOrNull()?.takeIf { n -> n in 1..500 }
                    ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit is 1 to 500")
            }
                ?: 100
        val unwatched = call.request.queryParameters["unwatched"] == "true"
        val query = call.request.queryParameters
        call.respond(
            catalog.items(
                call.parameters["id"]!!,
                query["sort"],
                unwatched,
                query["cursor"],
                limit,
                query["genre"],
                call.showsMusic(),
                query["kind"],
            ),
        )
    }
    get("/api/v1/library/home") { call.respond(catalog.home(call.showsMusic())) }
    get("/api/v1/library/search") {
        val query =
            call.request.queryParameters["q"]?.takeIf { it.length <= QUERY_LIMIT }
                ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "q is 1 to $QUERY_LIMIT characters")
        call.respond(catalog.search(query, SEARCH_RESULTS, call.showsMusic()))
    }
    get("/api/v1/library/lookup") { call.respond(catalog.lookup(lookupIds(call.request.queryParameters))) }
    get("/api/v1/library/items/{id}") { call.respond(catalog.item(call.parameters["id"]!!, call.showsMusic())) }
    get("/api/v1/library/items/{id}/radio") {
        call.respond(catalog.radio(call.parameters["id"]!!, call.radioLimit(), call.showsMusic()))
    }
    get("/api/v1/libraries/{id}/radio") {
        call.respond(catalog.libraryRadio(call.parameters["id"]!!, call.radioLimit(), call.showsMusic()))
    }
    lyricsRoute(libraries.lyrics)
    put("/api/v1/library/items/{id}/play-state") {
        call.respond(catalog.updatePlayState(call.parameters["id"]!!, call.appBody<UpdatePlayState>()))
    }
}

/** The `limit` of a radio request, 1 to 200 songs. */
private fun ApplicationCall.radioLimit(): Int = request.queryParameters["limit"]?.let {
    it.toIntOrNull()?.takeIf { n -> n in 1..RADIO_MAX }
        ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "limit is 1 to $RADIO_MAX")
} ?: RADIO_SIZE

private const val RADIO_MAX = 200

/** Whether the client names `music` in its [Wire.Header.LIBRARY_KINDS] header, so it may see Music libraries. */
internal fun ApplicationCall.showsMusic(): Boolean = request.headers.getAll(Wire.Header.LIBRARY_KINDS).orEmpty()
    .flatMap { it.split(',') }
    .any { it.trim().equals(Wire.LibraryKind.MUSIC, ignoreCase = true) }

/** The ids `GET /api/v1/library/lookup` asks for: at least one of `tmdb`, `tvdb` and `imdb`, each well formed. */
private fun lookupIds(query: Parameters): ProviderIds {
    val ids = ProviderIds(
        query["tmdb"]?.let { it.toIntOrNull()?.takeIf { id -> id > 0 } ?: badLookup() },
        query["tvdb"]?.let { it.toIntOrNull()?.takeIf { id -> id > 0 } ?: badLookup() },
        query["imdb"]?.lowercase()?.let { it.takeIf(IMDB_ID::matches) ?: badLookup() },
    )
    return if (ids.isEmpty) badLookup() else ids
}

private fun badLookup(): Nothing = throw ApiFailure(
    HttpStatusCode.BadRequest,
    "INVALID_REQUEST",
    "Give tmdb or tvdb as a number, or imdb as tt and digits",
)

/**
 * Posters and backdrops under `/artwork/{item}/{poster|backdrop}`, and scrub thumbnail sheets under
 * `/artwork/{item}/scrub/{n}`. Image loaders fetch these with no credentials, like logos, so the routes take none;
 * they serve only the images the library holds for that item.
 */
fun Route.artworkRoutes(libraries: LibraryService) {
    get("/artwork/{id}/scrub/{sheet}") {
        val sheet = call.parameters["sheet"]?.toIntOrNull()
            ?.let { libraries.scrub?.sheet(call.parameters["id"]!!, it) }
            ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown artwork")
        // The URL carries the version of the sheets, which a changed file replaces.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=604800")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respond(LocalPathContent(sheet, ContentType.Image.JPEG))
    }
    get("/artwork/{id}/{role}") {
        val artwork =
            libraries.artwork ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown artwork")
        val view =
            libraries.catalog.snapshot().views[call.parameters["id"]!!]
                ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown artwork")
        val source = when (call.parameters["role"]) {
            "poster" -> view.poster
            "backdrop" -> view.backdrop
            else -> null
        } ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown artwork")
        val etag = "\"${source.version}\""
        call.response.header(HttpHeaders.ETag, etag)
        // The URL carries the version, so a changed image gets a new URL and the old one may be kept.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=604800")
        call.response.header("X-Content-Type-Options", "nosniff")
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) return@get call.respond(HttpStatusCode.NotModified)
        val image =
            artwork.image(source) ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown artwork")
        call.respond(LocalPathContent(image.file, ContentType.parse(image.contentType)))
    }
}

/**
 * Library playback: `/api/v1/vod-sessions` opens, renews, restarts and releases a session, an item's text subtitles
 * come as WebVTT from `/api/v1/library/items/{id}/subtitles/{index}`, and a session's media is under
 * `/media/vod/{id}/{epoch}/{secret}/{name}`, which takes no token: the secret in the path is the session's own.
 */
fun Route.vodRoutes(vod: VodSessions) {
    post("/api/v1/vod-sessions") {
        call.respond(HttpStatusCode.Created, vod.open(call.appBody<OpenVodPlayback>(), call.playbackClient()))
    }
    get("/api/v1/vod-sessions/{id}") { call.respond(vod.get(call.parameters["id"]!!)) }
    patch("/api/v1/vod-sessions/{id}") {
        call.respond(vod.update(call.parameters["id"]!!, call.appBody<UpdateVodPlayback>()))
    }
    delete("/api/v1/vod-sessions/{id}") {
        vod.release(call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/api/v1/library/items/{id}/subtitles/{index}") {
        val index =
            call.parameters["index"]?.toIntOrNull()
                ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "No such subtitle stream")
        val file = vod.subtitles.vtt(call.parameters["id"]!!, index)
        call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
        call.respond(LocalPathContent(file, ContentType.parse("text/vtt; charset=utf-8")))
    }
    route("/media/vod/{id}/{epoch}/{secret}/{name}") {
        // Direct play seeks with byte ranges; the HLS files are small and served whole.
        install(PartialContent)
        get {
            val params = call.parameters
            val media = params["epoch"]?.toIntOrNull()
                ?.let { epoch -> vod.files.media(params["id"]!!, epoch, params["secret"]!!, params["name"]!!) }
                ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown media")
            call.response.header(
                HttpHeaders.CacheControl,
                if (media.contentType.endsWith("mpegurl")) "no-store" else "private, max-age=3600",
            )
            call.respond(LocalPathContent(media.file, ContentType.parse(media.contentType)))
        }
    }
}

private const val QUERY_LIMIT = 100
private const val SEARCH_RESULTS = 50

/** A song's lyrics as LRC text: timed lines when they are synced, plain lines when not. */
private fun Route.lyricsRoute(lyrics: LibraryLyrics?) = get("/api/v1/library/items/{id}/lyrics") {
    val text = lyrics?.lyrics(call.parameters["id"]!!)
        ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "No lyrics for that item")
    call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
    call.respondText(text, ContentType.parse("text/plain; charset=utf-8"))
}
