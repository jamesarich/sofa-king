package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.settings.RuntimeValues
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.path
import io.ktor.http.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.path
import io.ktor.server.routing.query
import io.ktor.server.routing.route

/** The Settings page: the effective configuration, the runtime settings, the API token and the policy's change log. */
internal class SettingsPages(private val portal: Portal) {
    private val runtime get() = portal.runtime
    private val config get() = portal.config

    fun install(route: Route) = with(portal) {
        route.get("/settings") {
            val query = call.request.queryParameters
            val limit = query["changes"]?.toIntOrNull()?.coerceIn(1, CHANGES_LIMIT) ?: CHANGES_SHOWN
            val advanced = query["advanced"] == "1" || query.contains("changes")
            call.html(settingsPage(chrome(), settingsView(limit, openAdvanced = advanced)))
        }
        route.action("/settings/reveal-api-token") { session, _ ->
            val view = settingsView(CHANGES_SHOWN, revealApiToken = true, openAdvanced = true)
            call.html(settingsPage(chrome(call, session), view))
        }
        route.action("/settings/runtime/check") { _, form ->
            // The form asks, on every change, whether its values differ from what the server uses; the
            // answer is the Save control alone.
            val proposed = form.names().associateWith { form[it].orEmpty() }
            call.html(runtimeSaveFragment(runtimeChanged(runtime.settings.current, proposed)))
        }
        route.action("/settings/runtime") { session, form -> saveRuntime(call, session, form) }
        route.action("/policy/apply") { session, _ -> apply(call, session) }
    }

    private suspend fun saveRuntime(call: ApplicationCall, session: AdminSession, form: Parameters) {
        val reset = form["op"] == "reset"
        val proposed = if (reset) emptyMap() else RuntimeValues.NAMES.associateWith { form[it]?.trim().orEmpty() }
        try {
            val before = runtime.settings.current
            val after = runtime.settings.update(proposed)
            val message = when {
                reset -> "Runtime settings follow the environment again."
                before == after -> "Nothing changed."
                else -> "Saved the runtime settings; they apply now."
            }
            session.flash.set(Flash(message))
            seeOther(call, "/admin/settings#runtime")
        } catch (e: ApiFailure) {
            val view = settingsView(CHANGES_SHOWN)
            val refused = view.runtime!!.let {
                RuntimeSettingsView(it.current, it.environment, it.saved, it.plex, listOf(e.message), proposed)
            }
            call.html(
                settingsPage(
                    portal.chrome(call, session, Flash("Not saved: ${e.message}", Tone.BAD)),
                    SettingsView(view, runtime = refused),
                ),
                HttpStatusCode.BadRequest,
            )
        }
    }

    private suspend fun apply(call: ApplicationCall, session: AdminSession) {
        val policy = runtime.policy
        if (policy == null) {
            call.html(
                settingsPage(
                    portal.chrome(call, session, Flash("No lineup policy is configured.", Tone.BAD)),
                    settingsView(CHANGES_SHOWN, openAdvanced = true),
                ),
                HttpStatusCode.Conflict,
            )
            return
        }
        val result = policy.apply("api")
        val summary =
            "${thousands(result.enabledStations)} on of ${thousands(result.stations)}, " +
                if (result.changed) plural(result.changes, "field") + " changed." else "no change."
        val tone = if (result.findings.any { it.startsWith("feeds:") }) Tone.WARN else Tone.OK
        session.flash.set(Flash("Applied the policy: $summary", tone))
        seeOther(call, "/admin/settings?advanced=1#policy")
    }

    private suspend fun settingsView(
        changeLimit: Int,
        revealApiToken: Boolean = false,
        openAdvanced: Boolean = false,
    ): SettingsView {
        val policy = runtime.policy
        val document = policy?.document()
        return SettingsView(
            config?.let(::effectiveSettings).orEmpty(),
            document?.let { PolicySummary(it.path, it.revision, it.policy, policy.lastApplied()?.result) },
            policy?.changes(changeLimit).orEmpty(),
            changeLimit,
            apiTokenSet = config?.apiToken != null,
            revealedApiToken = config?.apiToken?.takeIf { revealApiToken },
            runtime = RuntimeSettingsView(
                runtime.settings.current,
                runtime.settings.environment,
                runtime.settings.saved,
                runtime.plex != null,
            ),
            openAdvanced = openAdvanced,
        )
    }
}

/** The change log entries the settings page shows at first, and the most it shows. */
private const val CHANGES_SHOWN = 50
internal const val CHANGES_LIMIT = 1000
