package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.NamedOutput
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import dev.jamesrich.sofaking.backend.http.deviceShare
import dev.jamesrich.sofaking.backend.http.hdhrChannels
import dev.jamesrich.sofaking.backend.http.publicBase
import dev.jamesrich.sofaking.backend.http.tokenQuery
import io.ktor.http.Url
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.host
import io.ktor.server.routing.port
import io.ktor.server.routing.route

/** The Plex page: the playlist and guide URLs, the emulated tuners and who may use them, and Plex sync. */
internal class PlexPages(private val portal: Portal) {
    private val runtime get() = portal.runtime
    private val hdhr get() = portal.hdhr

    fun install(route: Route) = with(portal) {
        route.get("/plex") { call.html(plexPage(chrome(), plexView(call, reveal = false))) }
        route.action("/plex/reveal") { session, _ ->
            call.html(plexPage(chrome(call, session), plexView(call, reveal = true)))
        }
        route.action("/plex/allow") { session, form ->
            val address = form["address"].orEmpty().trim()
            val flash = if (address.isEmpty()) {
                Flash("Enter an IP address or CIDR block.", Tone.BAD)
            } else {
                try {
                    runtime.hdhrAccess.allow(address)
                    Flash("Allowed $address. It can use the tuners now.")
                } catch (e: IllegalArgumentException) {
                    Flash("Not allowed: ${e.message}", Tone.BAD)
                }
            }
            session.flash.set(flash)
            seeOther(call, "/admin/plex#access")
        }
        route.action("/plex/disallow") { session, form ->
            val address = form["address"].orEmpty().trim()
            runtime.hdhrAccess.remove(address)
            session.flash.set(Flash("Removed $address. It can no longer use the tuners."))
            seeOther(call, "/admin/plex#access")
        }
        route.action("/plex/outputs/save") { session, form ->
            val output = NamedOutput(
                form["name"].orEmpty(),
                commaList(form["groups"]),
                commaList(form["channels"]),
            )
            val flash = try {
                Flash("Saved the ${runtime.namedOutputs.put(output).name} output.")
            } catch (e: ApiFailure) {
                Flash("Not saved: ${e.message}", Tone.BAD)
            }
            session.flash.set(flash)
            seeOther(call, "/admin/plex#named-outputs")
        }
        route.action("/plex/outputs/delete") { session, form ->
            val name = form["name"].orEmpty()
            runtime.namedOutputs.delete(name)
            session.flash.set(Flash("Deleted the $name output."))
            seeOther(call, "/admin/plex#named-outputs")
        }
    }

    private suspend fun plexView(call: ApplicationCall, reveal: Boolean): PlexView {
        val base = publicBase(call)
        val enabled = hdhrChannels(runtime.lineup.entries())
        val byRange = runtime.policy != null
        val tokenNeeded = portal.outputs.token != null
        val shown = when {
            reveal -> tokenQuery(portal.outputs.token)
            tokenNeeded -> "?token=$HIDDEN_TOKEN"
            else -> ""
        }
        return PlexView(
            playlist = "$base/output/playlist.m3u$shown",
            guide = "$base/output/guide.xml$shown",
            tokenConfigured = tokenNeeded,
            revealed = reveal && tokenNeeded,
            hdhrEnabled = hdhr.enabled,
            devices = devices(base, enabled, byRange),
            devicesNeeded = if (byRange &&
                hdhr.enabled
            ) {
                (enabled.size + hdhr.deviceSize - 1) / hdhr.deviceSize
            } else {
                null
            },
            enabledChannels = enabled.size,
            tuners = hdhr.tuners,
            status = portal.plexStatus?.status(),
            access = if (hdhr.enabled) runtime.hdhrAccess.status(true) else null,
            namedOutputs = runtime.namedOutputs.list().map { output ->
                val path = "$base/output/${output.name}"
                NamedOutputRow(output, "$path/playlist.m3u$shown", "$path/guide.xml$shown")
            },
        )
    }

    /** Each emulated tuner device at the address Plex adds it by, with how many channels it carries. */
    private fun devices(base: String, enabled: List<LineupEntryRecord>, byRange: Boolean): List<HdhrDevice> {
        if (!hdhr.enabled) return emptyList()
        val host = Url(base).host.let { if (it.contains(':')) "[$it]" else it }
        val ports = hdhr.publicPorts(Url(base).port)
        return (0 until hdhr.devices).map { device ->
            val name = if (device == 0) hdhr.friendlyName else "${hdhr.friendlyName} ${device + 1}"
            HdhrDevice(name, "$host:${ports[device]}", deviceShare(enabled, device, hdhr, byRange).size)
        }
    }
}

internal const val HIDDEN_TOKEN = "••••••••"
