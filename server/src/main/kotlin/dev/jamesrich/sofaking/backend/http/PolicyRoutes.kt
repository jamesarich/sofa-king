package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.PolicyService
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** The lineup policy under the admin route, read-only: the loaded document, a preview against the lineup, and one station's candidates. */
fun Route.policyRoutes(policy: PolicyService?) {
    fun loaded(): PolicyService = policy ?: throw ApiFailure(404, "POLICY_NOT_CONFIGURED", "No lineup policy is configured; set SOFA_POLICY")
    get("/policy") { call.respond(loaded().document()) }
    get("/policy/preview") { call.respond(loaded().preview()) }
    get("/stations/{key}") { call.respond(loaded().explain(call.parameters["key"]!!)) }
}
