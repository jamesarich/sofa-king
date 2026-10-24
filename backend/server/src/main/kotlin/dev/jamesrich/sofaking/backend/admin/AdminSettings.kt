package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.PolicyChangeEntry
import dev.jamesrich.sofaking.backend.settings.RuntimeValues
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.map
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.time
import kotlinx.html.title
import kotlinx.html.tr

/**
 * One effective setting: the variable that sets it, its value as the server uses it, and whether that is the default.
 */
internal class Setting(val name: String, val value: String, val isDefault: Boolean, val note: String? = null)

internal class SettingGroup(val title: String, val settings: List<Setting>)

/**
 * A provider or guide from the source file, described without its URL's path, query,
 * headers' values or options' values.
 */
internal class SourceSummary(
    val id: String,
    val kind: String,
    val where: String,
    val refresh: String,
    val notes: List<String>,
)

internal class PolicySummary(
    val path: String,
    val revision: String,
    val policy: LineupPolicy,
    val lastRun: PolicyApplyResult?,
)

/**
 * The runtime settings as the page edits them: [current] is what the server uses, [environment] what it started with,
 * [saved] the names whose value comes from the database. [plex] says whether Plex sync is configured, so the interval
 * reads as applying to it or as waiting for it. [errors] are a refused save's messages, and [proposed] its values.
 */
internal class RuntimeSettingsView(
    val current: RuntimeValues,
    val environment: RuntimeValues,
    val saved: Set<String>,
    val plex: Boolean,
    val errors: List<String> = emptyList(),
    val proposed: Map<String, String>? = null,
)

internal class SettingsView(
    val groups: List<SettingGroup>,
    val policy: PolicySummary?,
    val changes: List<PolicyChangeEntry>,
    val changeLimit: Int,
    val apiTokenSet: Boolean = false,
    /** The API token, only on the page answering the reveal action. */
    val revealedApiToken: String? = null,
    val runtime: RuntimeSettingsView? = null,
    /**
     * Whether the Advanced section starts open: after an action inside it, or when the page is asked for `advanced=1`.
     */
    val openAdvanced: Boolean = false,
) {
    constructor(
        view: SettingsView,
        runtime: RuntimeSettingsView?,
    ) : this(
        view.groups,
        view.policy,
        view.changes,
        view.changeLimit,
        view.apiTokenSet,
        view.revealedApiToken,
        runtime,
        view.openAdvanced,
    )
}

/**
 * The Save control of the runtime settings form: "No changes" and disabled while the fields match what the server uses.
 */
internal fun FlowContent.runtimeSave(changed: Boolean) = div("actions") {
    id = "runtime-save"
    button(type = ButtonType.submit, classes = "primary") {
        name = "op"
        value = "save"
        disabled = !changed
        +(if (changed) "Save" else "No changes")
    }
    button(type = ButtonType.submit, classes = "quiet") {
        name = "op"
        value = "reset"
        +"Back to the environment's values"
    }
}

/** The Save control alone, as the answer to the form's check request. */
internal fun runtimeSaveFragment(changed: Boolean): String =
    kotlinx.html.stream.createHTML(prettyPrint = false).div { runtimeSave(changed) }

/** Whether [proposed], the form's values by name, differs from what the server uses. */
internal fun runtimeChanged(current: RuntimeValues, proposed: Map<String, String>): Boolean =
    current.byName().any { (name, value) -> proposed[name]?.trim()?.lowercase() != value }

/**
 * The source file's providers and guides, as a folded section: what each is and where it comes from, never a URL
 * path, query, header value or option value.
 */
internal fun FlowContent.sourceFilePanel(providers: List<SourceSummary>, guides: List<SourceSummary>) =
    panel("Source file", id = "sources", aside = {
        span("muted") { +"URL paths, queries and header values are not shown" }
    }) {
        if (providers.isEmpty() && guides.isEmpty()) {
            p("muted") {
                +(
                    "No providers or guides. Add sources.json to the data directory, or set SOFA_SOURCES to a source " +
                        "file, and restart."
                    )
            }
            return@panel
        }
        details {
            summary { +(plural(providers.size, "provider") + ", " + plural(guides.size, "guide") + " configured") }
            div("scroll") {
                table {
                    caption("sr") { +"Configured sources" }
                    thead {
                        tr {
                            col("ID")
                            col("Kind")
                            col("From")
                            col("Refresh")
                            col("Notes")
                        }
                    }
                    tbody {
                        for (source in providers + guides) {
                            tr {
                                th {
                                    attributes["scope"] = "row"
                                    +source.id
                                }
                                td { +source.kind }
                                td { +source.where }
                                td("nowrap") { +source.refresh }
                                td("muted small") { +source.notes.joinToString("; ") }
                            }
                        }
                    }
                }
            }
        }
    }

internal fun settingsPage(chrome: Chrome, view: SettingsView): String = layout("Settings", chrome, "/admin/settings") {
    val now = chrome.now
    pageHead(
        "Settings",
        "The settings that change at once. Everything else the server runs with, and the lineup policy's file, is " +
            "under Advanced.",
    )
    view.runtime?.let { runtimePanel(chrome, it) }
    details("advanced") {
        id = "advanced"
        if (view.openAdvanced) open = true
        summary {
            +"Advanced"
            span("muted small") { +" · the lineup policy, the startup settings and the change log" }
        }
        policyPanel(chrome, view.policy, now)
        h2("mt-16") { +"Startup settings" }
        p("muted small") {
            +"Read from the server's environment (.env) when it starts. Change one there and restart the server."
        }
        div("cols") {
            for (group in view.groups) {
                panel(group.title) {
                    div("settings") {
                        for (setting in group.settings) {
                            settingRow(setting)
                        }
                        if (group.title == "Tokens" && view.apiTokenSet) {
                            apiTokenReveal(chrome, view)
                        }
                    }
                }
            }
        }
        if (view.policy != null) changeLogPanel(view, now)
    }
}

private fun FlowContent.settingRow(setting: Setting) = div("setting") {
    div("name mono") { +setting.name }
    div("val") {
        when (setting.value) {
            SET -> chip(SET, Tone.OK)
            GENERATED -> chip(GENERATED, Tone.OK)
            UNSET -> span("muted") { +UNSET }
            else -> +setting.value
        }
        if (setting.isDefault && setting.value !in setOf(UNSET, SET, GENERATED)) {
            span("muted small") { +" · default" }
        }
        if (setting.note != null) div("muted small") { +setting.note }
    }
}

private fun FlowContent.apiTokenReveal(chrome: Chrome, view: SettingsView) = div("mt-12") {
    id = "api-token"
    val token = view.revealedApiToken
    if (token == null) {
        postForm("/admin/settings/reveal-api-token", chrome.session) {
            button(type = ButtonType.submit) { +"Reveal the API token" }
        }
    } else {
        div("muted small") { +"API token, for the Sofa King app and API clients:" }
        code { +token }
    }
}

private fun FlowContent.policyPanel(chrome: Chrome, policy: PolicySummary?, now: java.time.Instant) =
    panel("Lineup policy", id = "policy") {
        if (policy == null) {
            p("muted") {
                +(
                    "No lineup policy. Add policy.json to the data directory, or set SOFA_POLICY to a policy file in " +
                        "a writable directory, and restart; the server then builds the lineup from it."
                    )
            }
            return@panel
        }
        dl("kv") {
            dt { +"File" }
            dd { code { +policy.path } }
            dt { +"Revision" }
            dd {
                span("mono") {
                    attributes["title"] = policy.revision
                    +policy.revision.take(SHORT_REVISION)
                }
            }
            val p = policy.policy
            dt { +"Holds" }
            dd {
                +listOf(
                    plural(p.providers.size, "provider entry", "provider entries"),
                    plural(p.numbering.groups.size, "number block"),
                    plural(p.groups.map.size, "category mapping"),
                    plural(p.groups.weak.size, "weak category", "weak categories"),
                    plural(p.groups.patterns.size, "pattern"),
                    plural(p.stations.size, "station override"),
                    plural(p.names.aliases.size, "alias", "aliases"),
                    plural(p.names.learned.size, "learned alias", "learned aliases"),
                ).joinToString(", ")
            }
            dt { +"Last run" }
            dd {
                val run = policy.lastRun
                if (run == null) {
                    +"None since the server started"
                } else {
                    ago(run.appliedAt, now)
                    +(
                        ": ${thousands(run.enabledStations)} on of ${thousands(run.stations)}, " +
                            if (run.changed) plural(run.changes, "field") + " changed" else "no change"
                        )
                }
            }
        }
        div("actions mt-14") {
            postForm("/admin/policy/apply", chrome.session) {
                button(type = ButtonType.submit, classes = "primary") { +"Apply now" }
            }
            span("muted small") {
                +(
                    "Runs the policy over the current feeds and writes the lineup. Runs also follow every import and " +
                        "every 15 minutes."
                    )
            }
        }
    }

private fun FlowContent.changeLogPanel(view: SettingsView, now: java.time.Instant) =
    panel("Change log", id = "changes", aside = {
        if (view.changeLimit < CHANGES_LIMIT) {
            val more = minOf(CHANGES_LIMIT, view.changeLimit * CHANGES_GROWTH)
            a("/admin/settings?changes=$more&advanced=1#changes") { +"Show more" }
        }
    }) {
        if (view.changes.isEmpty()) {
            p("muted") { +"No policy run has changed the lineup yet." }
            return@panel
        }
        div("scroll") {
            table {
                caption("sr") { +"Policy change log" }
                thead {
                    tr {
                        col("When")
                        col("Station")
                        col("Field")
                        col("Before")
                        col("After")
                        col("Cause")
                    }
                }
                tbody {
                    for (entry in view.changes) {
                        tr {
                            val change = entry.change
                            td("nowrap") { ago(entry.appliedAt, now) }
                            td {
                                if (change.entryId != null) {
                                    a("/admin/lineup/${change.entryId}", classes = "rowlink") { +change.entryName }
                                } else {
                                    +change.entryName
                                }
                                change.key?.let {
                                    br()
                                    span("muted small mono") { +it }
                                }
                            }
                            td { +(change.field ?: "station") }
                            td("muted") { +(change.before ?: "–") }
                            td { +(change.after ?: "–") }
                            td("muted small") { +change.reason }
                        }
                    }
                }
            }
        }
    }

/** How many characters of a policy revision the page shows; the rest is in its title. */
private const val SHORT_REVISION = 12

/** How many times more change log entries each "Show more" asks for. */
private const val CHANGES_GROWTH = 4
