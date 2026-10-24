package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.path
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.path
import io.ktor.server.routing.route

/** The Sources page: the source file's services and playlists, the imports, and the policy's provider entries. */
internal class SourcePages(private val portal: Portal, private val editor: PolicyEditor) {
    private val runtime get() = portal.runtime

    fun install(route: Route) = with(portal) {
        route.get("/providers") {
            val chrome = chrome()
            val view = providersView()
            call.html(providersPage(chrome, view) { providerEntries(chrome, view, null, null) })
        }
        route.action("/sources/services") { session, form ->
            val starter = form.wantsStarter() && runtime.setup?.canWriteStarterPolicy == true
            setupWrite(call, session, form, "the services", starter) { document ->
                applyServicesForm(document, form).map { next ->
                    val builds = "Builds the starter lineup from them: one station per channel, grouped and numbered " +
                        "in blocks"
                    val lines = servicesChanges(document, next) +
                        listOfNotNull(builds.takeIf { starter && next.providers.isNotEmpty() })
                    next to
                        SetupConfirm(
                            "Save the services and restart?",
                            "/admin/sources/services",
                            lines,
                            form.setupFields(),
                        )
                }
            }
        }
        route.action("/sources/playlist") { session, form ->
            setupWrite(call, session, form, "the playlist") { document ->
                applyPlaylistForm(document, form).map { next ->
                    next to SetupConfirm(
                        "Add the playlist ${form["id"]?.trim()} and restart?",
                        "/admin/sources/playlist",
                        listOf("Adds an M3U playlist from ${hostOnly(form["url"])}"),
                        form.setupFields(),
                    )
                }
            }
        }
        route.action("/sources/remove") { session, form ->
            val id = form["id"]?.trim().orEmpty()
            setupWrite(call, session, form, "the source file") { document ->
                if (document.providers.none { it.id == id }) {
                    Result.failure(IllegalArgumentException("No provider named $id"))
                } else {
                    val confirm = SetupConfirm(
                        "Remove $id and restart?",
                        "/admin/sources/remove",
                        listOf("Its channels leave the lineup's stations as sources"),
                        form.setupFields(),
                    )
                    Result.success(document.copy(providers = document.providers.filter { it.id != id }) to confirm)
                }
            }
        }
        route.action("/providers/edit") { session, form ->
            val edit = PolicyEditor.Edit("/admin/providers", { providersEdit(form, it) }) { chrome, errors, confirm ->
                val view = providersView()
                providersPage(chrome, view) { providerEntries(chrome, view, errors, confirm) }
            }
            editor.edit(call, session, form, edit)
        }
    }

    /**
     * A write to the source file from the Sources page: [edit] turns the file as written into the next document and
     * the confirmation to show; a refused edit re-renders the page with its reasons and the form, a first submission
     * asks, and a confirmed one writes, restarts and answers the restarting page.
     */
    private suspend fun setupWrite(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        what: String,
        starter: Boolean = false,
        edit: (SourceDocument) -> Result<Pair<SourceDocument, SetupConfirm>>,
    ) {
        val setup = runtime.setup ?: return seeOther(call, "/admin/providers")
        val proposed = edit(setup.document())
        val (next, confirm) = proposed.getOrNull() ?: Pair(null, null)
        when {
            next == null || confirm == null ->
                refuse(call, session, form, proposed.exceptionOrNull()?.message ?: "The change is not valid")

            form["confirm"] != "yes" -> {
                val view = providersView()
                val chrome = portal.chrome(call, session)
                val asked = ProvidersView(view, SetupView(view.setup!!, proposed = form, confirm = confirm))
                call.html(providersPage(chrome, asked) { providerEntries(chrome, view, null, null) })
            }

            else -> try {
                val result = setup.replaceSources(next, starterPolicy = starter && next.providers.isNotEmpty())
                val policy = result.policyPath?.let { " and the starter lineup to $it" }.orEmpty()
                call.html(restartingPage(portal.theme(call), "Saved $what to ${result.path}$policy."))
            } catch (e: ApiFailure) {
                refuse(call, session, form, e.message, e.status)
            }
        }
    }

    /** The Sources page again, with why the write was refused and the form as it was posted. */
    private suspend fun refuse(
        call: ApplicationCall,
        session: AdminSession,
        form: Parameters,
        message: String,
        status: HttpStatusCode = HttpStatusCode.BadRequest,
    ) {
        val view = providersView()
        val chrome = portal.chrome(call, session, Flash("Not saved: ${message.lineSequence().first()}", Tone.BAD))
        val refused = ProvidersView(view, SetupView(view.setup!!, errors = message.lines(), proposed = form))
        call.html(providersPage(chrome, refused) { providerEntries(chrome, view, null, null) }, status)
    }

    private suspend fun providersView(): ProvidersView {
        val configs = runtime.feeds.configs.associateBy { it.id }
        val document = runtime.policy?.document()
        val entries = document?.policy?.providers
        val led = runtime.policy?.lastApplied()?.stations.orEmpty()
            .mapNotNull { station ->
                station.offeringIds.firstOrNull()?.let { runtime.feeds.published.offering(it)?.providerId }
            }
            .groupingBy { it }.eachCount()
        val rows = runtime.feeds.statuses.map { status ->
            val config = configs[status.id]
            val kind = when {
                config == null -> ""
                config.native && config.service != config.id -> "native: ${config.service}"
                config.native -> "native"
                else -> "M3U"
            }
            ProviderRow(status, kind, entries.orEmpty().withIndex().filter { it.value.id == status.id })
        }
        return ProvidersView(
            rows,
            runtime.guides.statuses,
            entries,
            document?.revision,
            led,
            providerSummaries = runtime.feeds.configs.map(::providerSummary),
            guideSummaries = runtime.guides.configs.map(::guideSummary),
            setup = runtime.setup?.let { SetupView.of(it.document(), it) },
        )
    }
}
