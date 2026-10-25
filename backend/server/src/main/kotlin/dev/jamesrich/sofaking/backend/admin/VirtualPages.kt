package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.VirtualChannel
import dev.jamesrich.sofaking.backend.api.VirtualSlot
import dev.jamesrich.sofaking.backend.api.VirtualSource
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.virtual.SourceKind
import dev.jamesrich.sofaking.backend.virtual.VirtualChannelService
import dev.jamesrich.sofaking.backend.virtual.VirtualClient
import dev.jamesrich.sofaking.backend.virtual.sourceChoices
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Duration
import java.time.ZoneId

/** The Virtual tab: the virtual channels, and adding, changing and deleting them. */
internal class VirtualPages(private val portal: Portal) {
    private val runtime get() = portal.runtime

    fun install(route: Route) = with(portal) {
        route.get("/virtual") { call.html(virtualChannelsPage(chrome(), view(call.request.queryParameters["edit"]))) }
        route.action("/virtual") { session, form ->
            save(call, session, null) { channels -> channels.create(channelOf(form)) }
        }
        route.action("/virtual/{id}") { session, form ->
            val id = call.parameters["id"]!!
            save(call, session, id) { channels -> channels.update(id, channelOf(form)) }
        }
        route.action("/virtual/{id}/delete") { session, _ ->
            val channels = service()
            val id = call.parameters["id"]!!
            val name = runCatching { channels.channel(id).name }.getOrDefault("the channel")
            channels.delete(id)
            session.flash.set(Flash("Deleted $name."))
            seeOther(call, "/admin/virtual")
        }
    }

    private suspend fun save(
        call: ApplicationCall,
        session: AdminSession,
        id: String?,
        write: suspend (VirtualChannelService) -> VirtualChannel,
    ) {
        val channels = service()
        try {
            val saved = write(channels)
            session.flash.set(Flash("Saved ${saved.name}."))
            seeOther(call, "/admin/virtual?edit=${saved.id}#schedule")
        } catch (e: ApiFailure) {
            if (e.status != HttpStatusCode.BadRequest) throw e
            session.flash.set(Flash("Not saved: ${e.message}", Tone.BAD))
            seeOther(call, if (id == null) "/admin/virtual#edit" else "/admin/virtual?edit=$id#edit")
        }
    }

    private fun service(): VirtualChannelService = runtime.virtual
        ?: throw ApiFailure(HttpStatusCode.NotFound, "NOT_FOUND", "Virtual channels need the libraries")

    private suspend fun view(edit: String?): VirtualView {
        val channels = service()
        val all = channels.channels()
        val editing = edit?.let { id -> all.firstOrNull { it.id == id } }
        val now = portal.clock.instant()
        val rows = all.map { channel ->
            val airing = runCatching { channels.schedule(channel.id, now, now) }.getOrNull()?.firstOrNull()
            VirtualRow(channel, airing?.title)
        }
        val ids = runtime.feeds.configs.filter { it.service == VirtualClient.SERVICE }.map { it.id }.toSet()
        val entries = runtime.policy?.document()?.policy?.providers
        return VirtualView(
            configured = ids.isNotEmpty(),
            inPolicy = entries?.let { list -> list.any { it.id in ids } },
            rows = rows,
            sources = runtime.libraries?.catalog?.snapshot()?.sourceChoices().orEmpty() +
                channels.live?.choices().orEmpty().map { (id, label) ->
                    VirtualSource(SourceKind.CHANNEL, id) to "Live channel: $label"
                },
            editing = editing,
            schedule = editing?.let { channels.schedule(it.id, now, now.plus(SCHEDULE_AHEAD)) }.orEmpty(),
            zone = ZoneId.systemDefault(),
        )
    }

    private companion object {
        val SCHEDULE_AHEAD: Duration = Duration.ofHours(12)
        const val SLOT_FIELDS = 52

        /** The channel the form describes: its numbered slots in order, those with a source. */
        fun channelOf(form: Parameters): VirtualChannel = VirtualChannel(
            name = form["name"].orEmpty(),
            number = form["number"],
            slots = (0 until SLOT_FIELDS).mapNotNull { index ->
                val source = sourceOf(form["source.$index"]) ?: return@mapNotNull null
                VirtualSlot(source, form["shuffle.$index"] == "true", form["start.$index"]?.ifBlank { null })
            },
            filler = sourceOf(form["filler"]),
            padMinutes = form["padMinutes"]?.toIntOrNull() ?: 0,
        )
    }
}
