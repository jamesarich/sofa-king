package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.SavedSecret
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.library.LIBRARY_SCAN_JOB
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.library.LibraryService
import dev.jamesrich.sofaking.backend.store.library
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.path
import io.ktor.server.routing.route

/** The Library page: adding, scanning, renaming and removing libraries, and the TMDB token. */
internal class LibraryPages(private val portal: Portal) {
    private val runtime get() = portal.runtime

    fun install(route: Route) = with(portal) {
        route.get("/library") { call.html(libraryPage(chrome(), libraryView())) }
        route.action("/library") { session, form -> add(call, session, form) }
        for (secret in SavedSecret.entries) {
            route.action("/library/${secret.anchor}") { session, form -> saveSecret(call, session, form, secret) }
        }
        route.action("/library/{id}/scan") { session, _ ->
            val id = call.parameters["id"]!!
            val library = runtime.store.library(id)
            if (library == null) {
                session.flash.set(Flash("That library is gone.", Tone.WARN))
            } else {
                libraries().requestScan(id)
                session.flash.set(Flash("Scanning ${library.name}.", Tone.OK, id))
            }
            seeOther(call, "/admin/library#libraries")
        }
        route.action("/library/{id}/refresh") { session, _ ->
            val id = call.parameters["id"]!!
            val library = runtime.store.library(id)
            if (library == null) {
                session.flash.set(Flash("That library is gone.", Tone.WARN))
            } else {
                libraries().requestScan(id, refresh = true)
                session.flash.set(Flash("Looking up ${library.name} on MusicBrainz again.", Tone.OK, id))
            }
            seeOther(call, "/admin/library#libraries")
        }
        route.action("/library/{id}/rename") { session, form ->
            val id = call.parameters["id"]!!
            try {
                val library = libraries().rename(id, form["name"].orEmpty())
                session.flash.set(Flash("Renamed to ${library.name}.", Tone.OK, id))
            } catch (e: ApiFailure) {
                session.flash.set(Flash(e.message, Tone.BAD, id))
            }
            seeOther(call, "/admin/library#libraries")
        }
        route.action("/library/{id}/remove") { session, form -> remove(call, session, form) }
    }

    private fun libraries(): LibraryService =
        runtime.libraries ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Resource not found")

    private suspend fun add(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val service = libraries()
        val proposed = listOf("name", "kind", "path").associateWith { form[it].orEmpty() }
        try {
            val kind = LibraryKind.of(proposed.getValue("kind"))
                ?: throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Choose what the library holds")
            val library = service.create(proposed.getValue("name"), kind, proposed.getValue("path"))
            session.flash.set(Flash("Added ${library.name}; its first scan is running.", Tone.OK, library.id))
            seeOther(call, "/admin/library#library-${library.id}")
        } catch (e: ApiFailure) {
            call.html(
                libraryPage(portal.chrome(call, session), libraryView(listOf(e.message), proposed)),
                HttpStatusCode.BadRequest,
            )
        }
    }

    private suspend fun saveSecret(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        secret: SavedSecret,
    ) {
        val setup = runtime.setup ?: return seeOther(call, "/admin/library#${secret.anchor}")
        try {
            setup.save(secret, form[secret.noun].orEmpty())
            call.html(
                restartingPage(
                    portal.theme(call),
                    "Saved the ${secret.service} ${secret.noun} to ${setup.secretsPath}. ${secret.effect}",
                ),
            )
        } catch (e: ApiFailure) {
            call.html(
                libraryPage(portal.chrome(call, session), libraryView(secretError = secret to e.message)),
                e.status,
            )
        }
    }

    private suspend fun remove(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val id = call.parameters["id"]!!
        val library = runtime.store.library(id)
        if (library == null) {
            session.flash.set(Flash("That library is already gone.", Tone.WARN))
            return seeOther(call, "/admin/library")
        }
        val ask = Ask(
            "Remove ${library.name}?",
            "The app stops showing it, and its watch progress is forgotten. The files in ${library.path} stay where " +
                "they are; add the folder again to bring it back.",
            "/admin/library/$id/remove",
            "/admin/library#libraries",
            "Remove",
        )
        with(portal) {
            call.confirmed(session, form, ask, { libraryPage(it, libraryView()) }) {
                libraries().delete(id)
                session.flash.set(Flash("Removed ${library.name}. Its files are untouched."))
                seeOther(call, "/admin/library")
            }
        }
    }

    private suspend fun libraryView(
        errors: List<String> = emptyList(),
        proposed: Map<String, String>? = null,
        secretError: Pair<SavedSecret, String>? = null,
    ): LibraryView {
        val service = libraries()
        return LibraryView(
            service.libraries(), service.counts(),
            service.roots.map { it.root.toString() },
            service.folders.choices().map { it.toString() },
            runtime.jobs.snapshot().any { it.id == LIBRARY_SCAN_JOB && it.running }, errors, proposed,
            service.metadata?.enabled == true,
            fanart = portal.config?.fanartKey != null,
            listenBrainz = portal.config?.listenBrainzToken != null,
            settable = SavedSecret.entries.filterTo(mutableSetOf()) { runtime.setup?.fromEnvironment(it) == false },
            secretError = secretError,
            suggestions = service.folders.suggestions(),
        )
    }
}
