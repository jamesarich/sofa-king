package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.PolicyService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

internal const val POLICY_BODY_LIMIT = 4 * 1024 * 1024L

/** The `If-Match` revision without quotes or a weak prefix; null when absent. */
internal fun ApplicationCall.ifMatch(): String? =
    request.headers[HttpHeaders.IfMatch]?.trim()?.removePrefix("W/")?.trim('"')?.takeIf { it.isNotEmpty() }

private suspend fun ApplicationCall.policyBody(): ByteArray {
    val bytes = receiveChannel().readBuffer(POLICY_BODY_LIMIT + 1).readByteArray()
    if (bytes.size > POLICY_BODY_LIMIT) throw ApiFailure(413, "REQUEST_TOO_LARGE", tooLargeMessage(POLICY_BODY_LIMIT))
    return bytes
}

/** The lineup policy under the admin route: the document and its writes, preview, apply, findings, the change log and one station's candidates. */
fun Route.policyRoutes(policy: PolicyService?) {
    fun loaded(): PolicyService = policy ?: throw ApiFailure(404, "POLICY_NOT_CONFIGURED", "No lineup policy is configured; set SOFA_POLICY")
    get("/policy") {
        val document = loaded().document()
        call.response.header(HttpHeaders.ETag, "\"${document.revision}\"")
        call.respond(document)
    }
    put("/policy") {
        val service = loaded()
        val revision = call.ifMatch() ?: throw ApiFailure(428, "PRECONDITION_REQUIRED", "Send If-Match with the policy revision from GET /api/v1/admin/policy")
        val result = service.replace(revision, call.policyBody())
        call.response.header(HttpHeaders.ETag, "\"${result.document.revision}\"")
        call.respond(result)
    }
    get("/policy/preview") { call.respond(loaded().preview()) }
    post("/policy/apply") { call.respond(loaded().apply("api")) }
    get("/policy/findings") { call.respond(loaded().findings()) }
    get("/policy/changes") { call.respond(loaded().changes(call.request.queryParameters["limit"]?.toIntOrNull() ?: 100)) }
    get("/stations/{key}") { call.respond(loaded().explain(call.parameters["key"]!!)) }
}
