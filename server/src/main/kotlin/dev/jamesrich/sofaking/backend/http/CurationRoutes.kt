package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.CurationImport
import dev.jamesrich.sofaking.backend.api.CurationRule
import dev.jamesrich.sofaking.backend.api.CurationSettings
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.CurationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/** The pipeline's files carry fields the rules do not use, so the import reads them leniently. */
private val importJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private const val CURATION_IMPORT_LIMIT = 4 * 1024 * 1024L

/** Curation settings, rules, the dry-run preview, a run on demand and the change log, under the admin route. */
fun Route.curationRoutes(curation: CurationService) {
    route("/curation") {
        get("/settings") { call.respond(curation.settings()) }
        put("/settings") { call.respond(curation.updateSettings(call.body<CurationSettings>())) }
        get("/rules") { call.respond(curation.rules()) }
        post("/rules") { call.respond(HttpStatusCode.Created, curation.createRule(call.body<CurationRule>())) }
        put("/rules/{id}") { call.respond(curation.updateRule(call.parameters["id"]!!, call.body<CurationRule>())) }
        delete("/rules/{id}") {
            curation.deleteRule(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/import") {
            val bytes = call.receiveChannel().readBuffer(CURATION_IMPORT_LIMIT + 1).readByteArray()
            if (bytes.size > CURATION_IMPORT_LIMIT) throw ApiFailure(413, "REQUEST_TOO_LARGE", "Import exceeds 4 MiB")
            call.respond(curation.import(importJson.decodeFromString<CurationImport>(bytes.toString(Charsets.UTF_8))))
        }
        get("/preview") { call.respond(curation.preview()) }
        post("/run") { call.respond(curation.run(force = true)) }
        get("/changes") { call.respond(curation.changes((call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000))) }
    }
}
