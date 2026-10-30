package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.iptvorg.IptvOrgDecisionRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The iptv-org database under the admin route: its status, the matches to decide, a decision and a download now. */
fun Route.iptvOrgRoutes(runtime: LiveRuntime) {
    fun matcher() = runtime.iptvOrg
        ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")
    get("/iptv-org") { call.respond(matcher().overview()) }
    post("/iptv-org/matches") {
        matcher().decide(call.body<IptvOrgDecisionRequest>())
        call.respond(matcher().overview())
    }
    post("/iptv-org/refresh") {
        val matcher = matcher()
        matcher.download()
        call.respond(matcher.overview())
    }
}
