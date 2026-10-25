package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.CreateLibrary
import dev.jamesrich.sofaking.backend.api.LibraryFolders
import dev.jamesrich.sofaking.backend.api.LibraryInfo
import dev.jamesrich.sofaking.backend.api.LibraryScanInfo
import dev.jamesrich.sofaking.backend.api.ScanPath
import dev.jamesrich.sofaking.backend.api.ScanQueued
import dev.jamesrich.sofaking.backend.api.UpdateLibrary
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.library.LibraryService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

/** The media libraries under the admin route: list, add, rename, remove, and scan one or the ones holding a path. */
internal fun Route.libraryAdminRoutes(libraries: LibraryService?) {
    fun loaded(): LibraryService = libraries
        ?: throw ApiFailure(
            HttpStatusCode.NotFound,
            "LIBRARIES_NOT_AVAILABLE",
            "Media libraries are not available on this server",
        )
    get("/libraries") {
        val service = loaded()
        val counts = service.counts()
        call.respond(service.libraries().map { it.info(counts[it.id].orEmpty()) })
    }
    get("/libraries/folders") {
        val service = loaded()
        call.respond(
            LibraryFolders(
                service.roots.map {
                    it.root.toString()
                },
                service.folders.choices().map { it.toString() },
            ),
        )
    }
    post("/libraries") {
        val request = call.body<CreateLibrary>()
        val kind =
            LibraryKind.of(request.kind)
                ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "kind is movies, shows or videos")
        call.respond(HttpStatusCode.Created, loaded().create(request.name, kind, request.path).info(emptyMap()))
    }
    get("/libraries/{id}") {
        val service = loaded()
        val id = call.parameters["id"]!!
        call.respond(service.library(id).info(service.counts()[id].orEmpty()))
    }
    patch("/libraries/{id}") {
        val service = loaded()
        val id = call.parameters["id"]!!
        call.respond(service.rename(id, call.body<UpdateLibrary>().name).info(service.counts()[id].orEmpty()))
    }
    delete("/libraries/{id}") {
        loaded().delete(call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/libraries/scan") {
        val queued = loaded().requestScanAt(call.body<ScanPath>().path)
        if (queued.isEmpty()) {
            throw ApiFailure(HttpStatusCode.NotFound, "LIBRARY_NOT_FOUND", "No library holds that path")
        }
        call.respond(HttpStatusCode.Accepted, ScanQueued(queued.map { it.id }))
    }
    post("/libraries/{id}/scan") {
        val result = loaded().scan(call.parameters["id"]!!)
        call.respond(
            LibraryScanInfo(
                result.files,
                result.added,
                result.updated,
                result.missing,
                result.removed,
                result.unavailable,
            ),
        )
    }
}

internal fun LibraryRecord.info(items: Map<String, Int>) =
    LibraryInfo(id, name, kind.wire, path, createdAt, items, scannedAt, scanOk, scanNote)
