package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.http.publicBase
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
        route.action("/viewers/cancel") { session, _ ->
            runtime.pairing.cancel()
            session.flash.set(Flash("Canceled the pairing code."))
            seeOther(call, "/admin/viewers")
        }
        route.action("/viewers/{clientId}/forget") { session, _ ->
            val clientId = call.parameters["clientId"]!!
            val viewer = runtime.store.viewers().firstOrNull { it.clientId == clientId }
            if (viewer == null || !runtime.store.forgetViewer(clientId)) {
                session.flash.set(Flash("That device is already gone.", Tone.WARN))
            } else {
                session.flash.set(Flash("Forgot ${viewer.name.ifEmpty { "the device" }}."))
            }
            seeOther(call, "/admin/viewers#devices")
        }
    }

    /** The viewers the server knows and the live pairing code, with the address this request reached the server at. */
    private suspend fun viewersView(call: ApplicationCall): ViewersView {
        val base = publicBase(call)
        return ViewersView(runtime.store.viewers(), runtime.pairing.current()?.let { PairingView(it, base) }, base)
    }
}
