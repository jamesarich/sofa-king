package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.http.publicBase
import dev.jamesrich.sofaking.backend.http.servesWebApp
import dev.jamesrich.sofaking.backend.http.viewersWithTokens
import dev.jamesrich.sofaking.backend.http.webAppPairingPath
import dev.jamesrich.sofaking.backend.store.forgetViewer
import dev.jamesrich.sofaking.backend.store.viewers
import io.ktor.http.parameters
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** The Viewers page: pairing codes and the devices the server knows. */
internal class ViewerPages(private val portal: Portal) {
    private val runtime get() = portal.runtime

    fun install(route: Route) = with(portal) {
        route.get("/viewers") { call.html(viewersPage(chrome(), viewersView(call))) }
        route.action("/viewers/pair") { session, _ ->
            runtime.pairing.issue()
            call.html(viewersPage(chrome(call, session), viewersView(call)))
        }
        // A new code, claimed by the web app this browser opens: the browser leaves the portal for the app's page.
        route.action("/viewers/open-app") { _, _ ->
            if (config?.servesWebApp() != true) return@action seeOther(call, "/admin/viewers")
            seeOther(call, webAppPairingPath(runtime.pairing.issue().code))
        }
        route.action("/viewers/cancel") { session, _ ->
            runtime.pairing.cancel()
            session.flash.set(Flash("Canceled the pairing code."))
            seeOther(call, "/admin/viewers")
        }
        route.action("/viewers/{clientId}/forget") { session, form ->
            val clientId = call.parameters["clientId"]!!
            val viewer = runtime.store.viewers().firstOrNull { it.clientId == clientId }
            val name = viewer?.name?.ifEmpty { null } ?: "the device"
            val ask = Ask(
                "Forget $name?",
                "Its saved preferences go and its token stops working. It pairs again to reconnect.",
                "/admin/viewers/$clientId/forget",
                "/admin/viewers#devices",
                "Forget",
            )
            call.confirmed(session, form, ask, { viewersPage(it, viewersView(call)) }) {
                val revoked = runtime.tokens.revokeClient(clientId)
                if (!runtime.store.forgetViewer(clientId) && revoked == 0) {
                    session.flash.set(Flash("That device is already gone.", Tone.WARN))
                } else {
                    val token = if (revoked > 0) " and revoked its token; it pairs again to reconnect" else ""
                    session.flash.set(Flash("Forgot $name$token."))
                }
                seeOther(call, "/admin/viewers#devices")
            }
        }
    }

    /** The viewers the server knows and the live pairing code, with the address this request reached the server at. */
    private suspend fun viewersView(call: ApplicationCall): ViewersView {
        val base = publicBase(call)
        return ViewersView(
            runtime.viewersWithTokens(),
            runtime.pairing.current()?.let { PairingView(it, base) },
            base,
            webApp = portal.config?.servesWebApp() == true,
        )
    }
}
