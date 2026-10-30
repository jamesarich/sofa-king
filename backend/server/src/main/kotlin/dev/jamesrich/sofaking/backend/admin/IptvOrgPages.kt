package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.iptvorg.IptvOrgDecisionRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The iptv-org page: the downloads' status, the matches to decide and the stations the blocklist holds. */
internal class IptvOrgPages(private val portal: Portal) {
    private val runtime get() = portal.runtime

    fun install(route: Route) = with(portal) {
        route.get("/iptv-org") {
            val matcher = runtime.iptvOrg
            if (matcher == null) {
                call.html("The server runs without iptv-org support", HttpStatusCode.NotFound)
            } else {
                call.html(iptvOrgPage(chrome(), matcher.overview()))
            }
        }
        route.action("/iptv-org/matches") { session, form ->
            val matcher = runtime.iptvOrg ?: return@action
            val request = IptvOrgDecisionRequest(
                form["station"].orEmpty(),
                form["decision"].orEmpty(),
                form["channelId"]?.takeIf { it.isNotBlank() },
            )
            try {
                matcher.decide(request)
                session.flash.set(Flash("Saved. The policy runs again."))
                seeOther(call, "/admin/iptv-org")
            } catch (e: ApiFailure) {
                call.html(
                    iptvOrgPage(
                        portal.chrome(call, session, Flash("Not saved: ${e.message}", Tone.BAD)),
                        matcher.overview(),
                        e.message,
                    ),
                    e.status,
                )
            }
        }
        route.action("/iptv-org/refresh") { session, _ ->
            runtime.jobs.runNow(IPTV_ORG_JOB)
            session.flash.set(Flash("Download requested; the Jobs page shows how it went."))
            seeOther(call, "/admin/iptv-org")
        }
    }

    private companion object {
        const val IPTV_ORG_JOB = "iptv-org"
    }
}
