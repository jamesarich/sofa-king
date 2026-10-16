package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.ActiveSession
import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.PolicyUnmappedCategory
import dev.jamesrich.sofaking.backend.api.ProviderStatus
import dev.jamesrich.sofaking.backend.api.SourcePin
import java.time.Duration
import java.time.Instant
import kotlinx.html.*

/** Where the docs an attention item links live: this repository's backend docs, by file and heading. */
internal const val DOCS_BASE = "https://github.com/jamesarich/sofa-king/blob/main/backend/docs/"

/**
 * One thing on the overview that needs the operator, where to fix it, and the doc that explains it. [key] names the
 * kind of item, and [fingerprint] its text, so an ignored item comes back when what it says changes.
 */
internal class Attention(val tone: Tone, val title: String, val detail: String?, val href: String, val action: String, val key: String, val doc: String? = null) {
    val fingerprint: String = sha256Hex("$title\n${detail.orEmpty()}").take(16)
}

internal fun sha256Hex(text: String): String =
    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

/** Enabled stations by the state of their guide. */
internal class GuideCoverage(val available: Int, val stale: Int, val empty: Int, val unassigned: Int) {
    val total: Int get() = available + stale + empty + unassigned
    val percent: Int get() = if (total == 0) 0 else (available * 100) / total
}

internal class PlexSummary(val lastSyncAt: String?, val lastError: String?, val dvrDevices: Int, val mapped: Int, val missing: Int, val syncMinutes: Int)

/** One live session on the overview: the session, its channel's name and what that channel airs now. */
internal class SessionRow(val active: ActiveSession, val channel: String?, val onNow: String?)

/** A session the operator asked to stop, waiting for the confirmation. */
internal class StopConfirm(val row: SessionRow)

internal class OverviewView(
    val attention: List<Attention>,
    /** Items the operator ignored until they change. */
    val ignored: List<Attention> = emptyList(),
    val stations: Int, val enabled: Int, val guide: GuideCoverage,
    /** Null without a lineup policy. */
    val required: Int?, val requiredOff: Int?, val suggestions: Int?, val findings: Int?, val unverified: Int?,
    val providersOk: Int, val providersTotal: Int,
    /** Null when Plex sync is not configured. */
    val plex: PlexSummary?,
    val sessions: List<SessionRow>,
    val lastRun: PolicyApplyResult?, val policy: Boolean,
    val stop: StopConfirm? = null,
) {
    /** [view] with a stop confirmation. */
    constructor(view: OverviewView, stop: StopConfirm?) : this(
        view.attention, view.ignored, view.stations, view.enabled, view.guide, view.required, view.requiredOff, view.suggestions, view.findings, view.unverified,
        view.providersOk, view.providersTotal, view.plex, view.sessions, view.lastRun, view.policy, stop,
    )
}

internal class LineupView(
    val filter: LineupFilter, val rows: List<StationRow>, val matches: Int, val total: Int, val pages: Int, val groups: List<String>,
    /** The policy revision the row actions write against; null without a policy. */
    val revision: String?,
    /** Groups a station can be put in: the lineup's groups and the policy's numbered groups. */
    val groupChoices: List<String> = groups,
    val enabled: Int = 0,
    /** Every row's checkbox starts checked, from the "Select all on this page" link. */
    val selectAll: Boolean = false,
    /** The entry ids whose checkboxes stay checked, after a refused bulk edit. */
    val selected: Set<String> = emptySet(),
    /** A bulk edit waiting for the operator. */
    val confirm: Confirm? = null,
) {
    /** [view] with the selection state of a bulk edit. */
    constructor(view: LineupView, selectAll: Boolean = view.selectAll, selected: Set<String> = view.selected, confirm: Confirm? = view.confirm) : this(
        view.filter, view.rows, view.matches, view.total, view.pages, view.groups, view.revision, view.groupChoices, view.enabled, selectAll, selected, confirm,
    )
}

internal class SourceLine(val id: String, val provider: String, val channel: String?, val protocol: String, val status: String, val disabled: Boolean = false)

internal class StationView(
    val row: StationRow, val sources: List<SourceLine>, val guideName: String?, val filter: LineupFilter, val revision: String?, val groupChoices: List<String>,
    val error: String? = null,
    /** Whether a probe worker runs, so "Probe now" has somewhere to go. */
    val probes: Boolean = false,
    /** A source the operator asked to disable, waiting for the confirmation. */
    val disableConfirm: SourceLine? = null,
) {
    constructor(view: StationView, disableConfirm: SourceLine?) : this(view.row, view.sources, view.guideName, view.filter, view.revision, view.groupChoices, view.error, view.probes, disableConfirm)
}

internal class MergesView(
    val revision: String, val learned: Map<String, String>, val never: List<List<String>>, val suggestions: List<Pair<String, String>>,
    val findings: List<String>, val unmapped: List<PolicyUnmappedCategory>,
)

internal class RequiredRow(val station: PolicyStation, val pins: List<SourcePin>, val health: Map<String, Int>)

internal class RequiredView(val rows: List<RequiredRow>, val ran: Boolean)

internal class HdhrDevice(val name: String, val address: String, val channels: Int)

internal class PlexView(
    val playlist: String, val guide: String, val tokenConfigured: Boolean, val revealed: Boolean, val hdhrEnabled: Boolean,
    val devices: List<HdhrDevice>, val devicesNeeded: Int?, val enabledChannels: Int, val tuners: Int, val status: PlexStatus?,
)

/** A configured provider: its import status, what kind it is, and the policy entries that take its channels, by index. */
internal class ProviderRow(val status: ProviderStatus, val kind: String, val entries: List<IndexedValue<PolicyProvider>>)

internal class ProvidersView(
    val rows: List<ProviderRow>, val guides: List<GuideStatus>,
    /** The policy's provider entries in priority order; null without a policy. */
    val entries: List<PolicyProvider>?, val revision: String?,
    val stationsLed: Map<String, Int> = emptyMap(),
    /** The source file's providers and guides, described without their secrets, for the Source file section. */
    val providerSummaries: List<SourceSummary> = emptyList(), val guideSummaries: List<SourceSummary> = emptyList(),
)

internal fun signInPage(enabled: Boolean, error: String? = null, theme: String? = null): String = layout("Sign in", null, null, theme) {
    div("panel signin") {
        h1("mt-0") { +"Sign in" }
        if (!enabled) {
            p { +"The portal needs a token to sign in with. Set SOFA_ADMIN_TOKEN (or SOFA_API_TOKEN) and restart the server." }
            return@div
        }
        notice(error)
        form(action = "/admin/login", method = FormMethod.post, classes = "stack") {
            div("field") {
                label { htmlFor = "token"; +"Admin token" }
                passwordInput(name = "token") { id = "token"; required = true; autoFocus = true; attributes["autocomplete"] = "current-password" }
                span("hint") { +"SOFA_ADMIN_TOKEN, or SOFA_API_TOKEN when no admin token is set. A token the server generated is in secrets.json in the data directory: "; code { +"docker compose exec sofa-king-backend cat /config/secrets.json" } }
            }
            button(type = ButtonType.submit, classes = "primary") { +"Sign in" }
        }
    }
}

internal fun overviewPage(chrome: Chrome, view: OverviewView): String = layout("Home", chrome, "/admin") {
    val now = chrome.now
    val needs = view.attention.count { it.tone != Tone.OFF }
    pageHead("Home", if (needs == 0) "Everything is running. Nothing needs you right now." else "${plural(needs, "thing")} need${if (needs == 1) "s" else ""} you.")
    fun UL.items(items: List<Attention>, ignored: Boolean) {
        for (item in items) li(item.tone.css) {
            div("what") {
                chip(when (item.tone) { Tone.BAD -> "Broken"; Tone.WARN -> "Check"; Tone.OK -> "Info"; Tone.OFF -> "Info" }, item.tone)
                b { +item.title }
                if (item.detail != null) span("muted small") { +item.detail }
            }
            div("actions") {
                a(item.href, classes = "fix") { +item.action }
                if (item.doc != null) a(DOCS_BASE + item.doc, classes = "small") { attributes["rel"] = "noopener"; +"Docs" }
                postForm(if (ignored) "/admin/attention/show" else "/admin/attention/ignore", chrome.session, keepScroll = true) {
                    hiddenInput(name = "key") { value = item.key }
                    hiddenInput(name = "fingerprint") { value = item.fingerprint }
                    button(type = ButtonType.submit, classes = "small quiet") {
                        attributes["aria-label"] = (if (ignored) "Show again: " else "Ignore until it changes: ") + item.title
                        +(if (ignored) "Show again" else "Ignore")
                    }
                }
            }
        }
    }
    panel("Needs attention", id = "attention") {
        if (view.attention.isEmpty()) p("allgood") { +(if (view.ignored.isEmpty()) "All clear: every provider imported, Plex is in step and every required channel is on." else "Nothing new. Ignored items come back when they change.") }
        else ul("attention") { items(view.attention, ignored = false) }
        if (view.ignored.isNotEmpty()) details {
            summary { +"Ignored until it changes (${view.ignored.size})" }
            ul("attention") { items(view.ignored, ignored = true) }
        }
    }
    div("tiles") {
        a("/admin/lineup?state=enabled", classes = "tile") {
            span("label") { +"Channels on" }
            span("value") { +thousands(view.enabled); small { +" of ${thousands(view.stations)}" } }
            div("meter") { span { attributes["data-fill"] = (if (view.stations == 0) 0 else view.enabled * 100 / view.stations / 5 * 5).toString() } }
        }
        a("/admin/lineup?state=enabled&guide=missing", classes = "tile" + if (view.guide.total > 0 && view.guide.percent < 90) " warn" else "") {
            span("label") { +"Guide coverage" }
            span("value") { +"${view.guide.percent}%" }
            span("sub") { +"${thousands(view.guide.available)} of ${thousands(view.guide.total)} on-air channels have programmes ahead" }
        }
        val plex = view.plex
        a("/admin/plex", classes = "tile" + if (plex?.lastError != null || (plex?.missing ?: 0) > 0) " bad" else "") {
            span("label") { +"Plex sync" }
            when {
                plex == null -> { span("value") { small { +"Not set up" } }; span("sub") { +"Plex sync keeps Plex's channel maps in step; the Players page says how to turn it on." } }
                plex.lastError != null -> { span("value") { +"Failing" }; span("sub") { +plex.lastError } }
                plex.lastSyncAt == null -> { span("value") { small { +"Waiting" } }; span("sub") { +"No sync has run yet; every ${plex.syncMinutes} min." } }
                else -> {
                    span("value") { ago(plex.lastSyncAt, now) }
                    span("sub") { +"${plural(plex.dvrDevices, "tuner")} in Plex, ${thousands(plex.mapped)} channels mapped" }
                }
            }
        }
        if (view.required != null) a("/admin/required", classes = "tile" + if ((view.requiredOff ?: 0) > 0) " bad" else "") {
            span("label") { +"Required channels" }
            span("value") { +thousands(view.required - (view.requiredOff ?: 0)); small { +" of ${thousands(view.required)} on" } }
            span("sub") { +(if ((view.requiredOff ?: 0) == 0) "Every required channel is on." else "${plural(view.requiredOff!!, "channel")} off") }
        }
        if (view.policy) a("/admin/merges", classes = "tile" + if ((view.suggestions ?: 0) > 0) " warn" else "") {
            span("label") { +"Merges to settle" }
            span("value") { +(view.suggestions?.let(::thousands) ?: "–") }
            span("sub") { +(view.findings?.let { "${plural(it, "open finding")} from the last run" } ?: "No policy run yet") }
        }
        a("/admin/providers", classes = "tile" + if (view.providersOk < view.providersTotal) " bad" else "") {
            span("label") { +"Providers" }
            span("value") { +thousands(view.providersOk); small { +" of ${thousands(view.providersTotal)} importing" } }
            span("sub") { +(if (view.unverified != null) "${thousands(view.unverified)} channels waiting for a verified source" else "Channel and guide imports") }
        }
    }
    div("cols") {
        panel("Watching now", id = "watching", aside = { span("muted") { +plural(view.sessions.size, "session") } }) {
            if (view.sessions.isEmpty()) p("muted") { +"Nobody is watching." }
            else div("scroll") {
                table {
                    caption("sr") { +"Watching now" }
                    thead { tr { col("Channel"); col("On now"); col("Player"); col("Watching for"); col("Sent", "num"); col("Lease ends"); col("Action", "sr") } }
                    tbody {
                        for (row in view.sessions) {
                            val active = row.active
                            tr(chrome.flash?.takeIf { it.target == active.id }?.let { if (it.tone == Tone.BAD) "err-row" else "flash" }) {
                                td {
                                    if (row.channel != null) a("/admin/lineup/${active.channelId}", classes = "rowlink") { +row.channel } else +active.channelId
                                    if (active.busy) { +" "; chip("streaming", Tone.OK) }
                                    br(); span("muted small") { +"via ${active.providerId}" }
                                }
                                td { if (row.onNow != null) +row.onNow else span("muted") { +"No guide" } }
                                td {
                                    +(if (active.external) "M3U or Plex" else "Sofa King app")
                                    if (active.client != null) { br(); span("muted small mono") { +active.client } }
                                }
                                td("nowrap") { +since(active.startedAt, now) }
                                td("num nowrap") { +bytesText(active.bytes) }
                                td("nowrap") { ago(active.expiresAt, now) }
                                td {
                                    postForm("/admin/sessions/${active.id}/stop", chrome.session, keepScroll = true) {
                                        button(type = ButtonType.submit, classes = "small quiet") { attributes["aria-label"] = "Stop the session on ${row.channel ?: active.channelId}"; +"Stop" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            p("muted small") { +"Sent counts the bytes relayed to the player over this session. Stop ends the session; the player sees its stream end and may open a new one." }
        }
        if (view.policy) panel("Last lineup run", aside = { a("/admin/settings#changes") { +"Change log" } }) {
            val run = view.lastRun
            if (run == null) p("muted") { +"The policy has not built the lineup since the server started; runs follow imports and every 15 minutes." }
            else dl("kv") {
                dt { +"Ran" }; dd { ago(run.appliedAt, now) }
                dt { +"Stations" }; dd { +"${thousands(run.enabledStations)} on of ${thousands(run.stations)}" }
                dt { +"Changes" }; dd { +(if (run.changed) plural(run.changes, "field") + " changed" else "No change") }
                if (run.devicesNeeded != null) { dt { +"Tuners" }; dd { +"${run.devicesNeeded} needed, ${run.devicesConfigured} configured" } }
            }
        }
    }
    view.stop?.let { stop ->
        val name = stop.row.channel ?: stop.row.active.channelId
        askDialog(chrome, "Stop the session on $name?", "/admin/sessions/${stop.row.active.id}/stop", "/admin#watching", "Stop", danger = true) {
            p { +"The ${if (stop.row.active.external) "M3U or Plex player" else "Sofa King app"}${stop.row.active.client?.let { " at $it" } ?: ""} loses its stream. It can open the channel again." }
        }
    }
}

/** A running time as "12 min" or "2 h 5 min"; [startedAt] is an instant. */
internal fun since(startedAt: String, now: Instant): String {
    val from = runCatching { Instant.parse(startedAt) }.getOrNull() ?: return ""
    val s = Duration.between(from, now).seconds.coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min"
        s >= 60 -> "${s / 60} min"
        else -> "$s s"
    }
}

/** A byte count as "1.2 GB", "340 MB", "12 KB" or "0 B". */
internal fun bytesText(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(java.util.Locale.US, bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun stateChip(row: StationRow): Pair<String, Tone> {
    if (row.entry.enabled) return "On" to Tone.OK
    val reason = row.station?.disabledReason ?: row.entry.autoDisabledReason ?: "hidden"
    val tone = when (reason) { "unverified" -> Tone.WARN; "no-source", "unavailable", "missing" -> Tone.BAD; else -> Tone.OFF }
    val label = when (reason) {
        "policy", "hidden" -> "Hidden"; "unverified" -> "Unverified"; "no-source" -> "No source"; "unavailable" -> "Unavailable"; "missing" -> "Missing"; else -> reason
    }
    return label to tone
}

private fun guideChip(status: String): Pair<String, Tone> = when (status) {
    "available" -> "Guide" to Tone.OK
    "stale" -> "Guide ended" to Tone.WARN
    "empty" -> "Guide empty" to Tone.WARN
    else -> "No guide" to Tone.OFF
}

/** The filters as hidden fields, only those set, so an action brings the page back as it was. */
private fun FORM.filterFields(filter: LineupFilter) {
    for ((name, value) in listOf("q" to filter.q, "group" to filter.group, "state" to filter.state, "guide" to filter.guide)) {
        if (value.isNotEmpty()) hiddenInput(name = name) { this.value = value }
    }
    if (filter.required) hiddenInput(name = "required") { value = "true" }
    if (filter.override) hiddenInput(name = "override") { value = "true" }
    if (filter.changed) hiddenInput(name = "changed") { value = "true" }
    if (filter.sort.isNotEmpty()) hiddenInput(name = "sort") { value = filter.sort }
    if (filter.dir.isNotEmpty()) hiddenInput(name = "dir") { value = filter.dir }
    if (filter.page > 1) hiddenInput(name = "page") { value = filter.page.toString() }
}

internal fun lineupPage(chrome: Chrome, view: LineupView): String = layout("Channels", chrome, "/admin/lineup") {
    val filter = view.filter
    val session = chrome.session
    pageHead(
        "Channels",
        "${thousands(view.enabled)} on of ${thousands(view.total)}. " +
            if (view.revision != null) "Edits are written into the lineup policy and applied at once." else "Edits change the lineup directly.",
    )
    if (view.revision != null) tabs(chrome, "/admin/lineup", CHANNEL_TABS)
    form(action = "/admin/lineup", method = FormMethod.get, classes = "filters") {
        attributes["hx-trigger"] = "submit, change, input changed delay:400ms from:find input[type=search]"
        attributes["hx-sync"] = "this:replace"
        div("field wide") {
            label { htmlFor = "q"; +"Search" }
            searchInput(name = "q") { id = "q"; value = filter.q; placeholder = "Name, number or key"; attributes["autocomplete"] = "off" }
        }
        div("field") {
            label { htmlFor = "f-group"; +"Group" }
            select {
                id = "f-group"; name = "group"
                option { value = ""; +"All groups" }
                for (group in view.groups) option { value = group; selected = group == filter.group; +group }
            }
        }
        div("field") {
            label { htmlFor = "f-state"; +"State" }
            select {
                id = "f-state"; name = "state"
                val states = listOf("" to "Any state", "enabled" to "On", "hidden" to "Off") + if (view.revision != null) listOf("unverified" to "Unverified") else emptyList()
                for ((value, label) in states) option { this.value = value; selected = value == filter.state; +label }
            }
        }
        div("field") {
            label { htmlFor = "f-guide"; +"Guide" }
            select {
                id = "f-guide"; name = "guide"
                for ((value, label) in listOf("" to "Any guide", "available" to "Has programmes", "missing" to "Missing or ended")) {
                    option { this.value = value; selected = value == filter.guide; +label }
                }
            }
        }
        if (view.revision != null) div("checks") {
            label("check") {
                checkBoxInput(name = "required") { value = "true"; checked = filter.required }
                +"Required only"
            }
            label("check") {
                checkBoxInput(name = "override") { value = "true"; checked = filter.override }
                +"Has an override"
            }
            label("check") {
                checkBoxInput(name = "changed") { value = "true"; checked = filter.changed }
                +"Changed in the last run"
            }
        }
        if (filter.sort.isNotEmpty()) hiddenInput(name = "sort") { value = filter.sort }
        if (filter.dir.isNotEmpty()) hiddenInput(name = "dir") { value = filter.dir }
        div("actions") {
            button(type = ButtonType.submit) { +"Filter" }
            if (filter != LineupFilter()) a("/admin/lineup", classes = "btn quiet") { +"Clear" }
        }
    }
    div("summary") {
        attributes["role"] = "status"
        span { +("${thousands(view.matches)} of ${thousands(view.total)} channels" + if (view.pages > 1) " · page ${filter.page} of ${view.pages}" else "") }
        span("muted small legend") {
            span("swatch bad") { attributes["aria-hidden"] = "true" }; +" no healthy source "
            span("swatch warn") { attributes["aria-hidden"] = "true" }; +" no programmes ahead"
        }
    }
    if (view.rows.isEmpty()) div("panel") {
        p { +"No channel matches these filters." }
        a("/admin/lineup") { +"Show every channel" }
    }
    else div("panel tight") {
        // The row checkboxes belong to this form through their form attribute, so the row actions stay their own forms.
        form(action = "/admin/lineup/bulk", method = FormMethod.post, classes = "bulk") {
            id = "bulk"
            attributes["aria-label"] = "Selected channels"
            hiddenInput(name = "csrf") { value = session.csrf }
            view.revision?.let { revision -> hiddenInput(name = "revision") { value = revision } }
            filterFields(filter)
            span("label") { +"Selected:" }
            button(type = ButtonType.submit, classes = "small") { name = "op"; value = "hide"; +"Hide" }
            button(type = ButtonType.submit, classes = "small") { name = "op"; value = "show"; +"Show" }
            label("sr") { htmlFor = "bulk-group"; +"Group" }
            select {
                id = "bulk-group"; name = "target"
                option { value = ""; +(if (view.revision != null) "Policy decides" else "No group") }
                for (group in view.groupChoices) option { value = group; +group }
            }
            button(type = ButtonType.submit, classes = "small") { name = "op"; value = "group"; +"Set group" }
            span("muted small") {
                val query = filter.query()
                if (view.selectAll) a("/admin/lineup" + query.let { if (it.isEmpty()) "" else "?$it" }) { +"Clear selection" }
                else a("/admin/lineup?" + listOf(query, "select=page").filter { it.isNotEmpty() }.joinToString("&")) { +"Select all on this page" }
            }
        }
        div("scroll") {
            table("stack") {
                caption("sr") { +"Channels" }
                thead {
                    tr {
                        th(classes = "sel") { attributes["scope"] = "col"; span("sr") { +"Select" } }
                        fun TR.sortable(key: String, label: String, classes: String? = null) = th(classes = classes) {
                            attributes["scope"] = "col"
                            val sorted = filter.sort == key
                            if (sorted) attributes["aria-sort"] = if (filter.dir == "desc") "descending" else "ascending"
                            val next = filter.copy(sort = key, dir = if (sorted && filter.dir != "desc") "desc" else "asc", page = 1)
                            a("/admin/lineup?" + next.query(), classes = "sort") { +label }
                        }
                        sortable("number", "No.", "num")
                        sortable("name", "Channel")
                        sortable("group", "Group", "wide-only")
                        sortable("sources", "Sources", "wide-only num")
                        sortable("guide", "Guide", "wide-only")
                        sortable("state", "State")
                        col("Actions")
                    }
                }
                tbody {
                    // The notice about a row the operator just changed outranks its tint.
                    for (row in view.rows) tr(chrome.flash?.takeIf { it.target == row.entry.id }?.let { if (it.tone == Tone.BAD) "err-row" else "flash" } ?: row.tint?.let { "tint-${it.css}" }) {
                        val entry = row.entry
                        id = "row-${entry.id}"
                        val edit = "/admin/lineup/${entry.id}" + filter.query().let { if (it.isEmpty()) "" else "?$it" }
                        td("sel") {
                            attributes["data-cell"] = "select"
                            checkBoxInput(name = "id") {
                                value = entry.id
                                attributes["form"] = "bulk"
                                attributes["aria-label"] = "Select ${entry.name}"
                                checked = view.selectAll || entry.id in view.selected
                            }
                        }
                        td("num") { attributes["data-cell"] = "number"; +entry.number }
                        td {
                            attributes["data-cell"] = "name"
                            a(edit, classes = "rowlink") { +entry.name }
                            if (row.required) { +" "; chip("Required", Tone.WARN) }
                            if (row.overridden) { +" "; chip("Override", Tone.OFF) }
                        }
                        td("wide-only") { +(entry.group ?: "") }
                        td("wide-only num") {
                            attributes["title"] = "${row.healthy} healthy of ${entry.offeringIds.size}"
                            +entry.offeringIds.size.toString()
                            if (entry.offeringIds.isNotEmpty() && row.healthy == 0 && entry.enabled) span("error-text") { +" (0 healthy)" }
                        }
                        td("wide-only") { guideChip(row.guideStatus).let { (text, tone) -> chip(text, tone) } }
                        td("narrow-only") {
                            attributes["data-cell"] = "meta"
                            +listOfNotNull(entry.group, plural(entry.offeringIds.size, "source"), guideChip(row.guideStatus).first.lowercase()).joinToString(" · ")
                        }
                        td {
                            attributes["data-cell"] = "state"
                            stateChip(row).let { (text, tone) -> span("chip ${tone.css}") { attributes["title"] = row.state; +text } }
                        }
                        td {
                            attributes["data-cell"] = "actions"
                            div("actions") {
                                rowForm("/admin/lineup/${entry.id}/visibility", session, view) {
                                    hiddenInput(name = "enabled") { value = (!entry.enabled).toString() }
                                    button(type = ButtonType.submit, classes = "small") {
                                        attributes["aria-label"] = (if (entry.enabled) "Hide " else "Show ") + entry.name
                                        +(if (entry.enabled) "Hide" else "Show")
                                    }
                                }
                                a(edit, classes = "btn small quiet") { attributes["aria-label"] = "Edit ${entry.name}"; +"Edit" }
                            }
                        }
                    }
                }
            }
        }
    }
    if (view.pages > 1) nav("pager") {
        attributes["aria-label"] = "Pages"
        if (filter.page > 1) a("/admin/lineup?" + filter.query(filter.page - 1), classes = "btn") { attributes["rel"] = "prev"; +"Previous" }
        else span("btn") { attributes["aria-disabled"] = "true"; +"Previous" }
        span("muted") { +"Page ${filter.page} of ${view.pages}" }
        if (filter.page < view.pages) a("/admin/lineup?" + filter.query(filter.page + 1), classes = "btn") { attributes["rel"] = "next"; +"Next" }
        else span("btn") { attributes["aria-disabled"] = "true"; +"Next" }
    }
    confirmDialog(chrome, view.confirm)
}

/** A row action: the CSRF token, the policy revision and the filters set, so the page comes back as it was. */
private fun FlowContent.rowForm(action: String, session: AdminSession, view: LineupView, block: FORM.() -> Unit) =
    form(action = action, method = FormMethod.post, classes = "inline") {
        attributes["hx-swap"] = "innerHTML show:none"
        hiddenInput(name = "csrf") { value = session.csrf }
        view.revision?.let { revision -> hiddenInput(name = "revision") { value = revision } }
        filterFields(view.filter)
        block()
    }

internal fun stationPage(chrome: Chrome, view: StationView): String = layout(view.row.entry.name, chrome, "/admin/lineup") {
    val entry = view.row.entry
    val back = "/admin/lineup" + view.filter.query().let { if (it.isEmpty()) "" else "?$it" }
    p("mt-16") { a(back) { +"← Channels" } }
    h1 { +"${entry.number} · ${entry.name}" }
    div("chips mb-18") {
        stateChip(view.row).let { (text, tone) -> chip(text, tone) }
        if (view.row.required) chip("Required", Tone.WARN)
        guideChip(view.row.guideStatus).let { (text, tone) -> chip(text, tone) }
        entry.group?.let { chip(it, Tone.OFF) }
    }
    div("cols") {
        panel("Edit") {
            if (view.error != null) notice("Not saved: ${view.error}")
            form(action = "/admin/lineup/${entry.id}/edit", method = FormMethod.post, classes = "stack") {
                hiddenInput(name = "csrf") { value = chrome.session.csrf }
                view.revision?.let { revision -> hiddenInput(name = "revision") { value = revision } }
                filterFields(view.filter)
                div("formgrid") {
                    div("field span-all") {
                        label { htmlFor = "s-name"; +"Name" }
                        textInput(name = "name") { id = "s-name"; value = entry.name; required = true; maxLength = "256" }
                    }
                    div("field") {
                        label { htmlFor = "s-number"; +"Number" }
                        textInput(name = "number") { id = "s-number"; value = entry.number; required = true; maxLength = "32"; attributes["inputmode"] = "decimal" }
                    }
                    div("field") {
                        label { htmlFor = "s-group"; +"Group" }
                        // A list, so a typo cannot make a group with no number block.
                        select {
                            name = "group"; id = "s-group"
                            option { value = ""; selected = entry.group == null; +(if (view.revision != null) "Policy decides" else "No group") }
                            for (group in (view.groupChoices + listOfNotNull(entry.group)).distinct().sorted()) {
                                option { value = group; selected = group == entry.group; +group }
                            }
                        }
                    }
                }
                if (view.revision != null) p("muted small") { +"Saving writes these fields into the station's override in the policy, then applies it. Pick \"Policy decides\" to let the group map and patterns choose." }
                div("actions") {
                    button(type = ButtonType.submit, classes = "primary") { +"Save" }
                    a(back, classes = "btn quiet") { +"Cancel" }
                }
            }
        }
        panel("Visibility") {
            p("muted") {
                +(if (entry.enabled) "On: viewers see this channel in the app, the M3U playlist and Plex." else "Off: hidden from the app, the M3U playlist and Plex.")
            }
            form(action = "/admin/lineup/${entry.id}/visibility", method = FormMethod.post) {
                hiddenInput(name = "csrf") { value = chrome.session.csrf }
                view.revision?.let { revision -> hiddenInput(name = "revision") { value = revision } }
                hiddenInput(name = "from") { value = "station" }
                filterFields(view.filter)
                hiddenInput(name = "enabled") { value = (!entry.enabled).toString() }
                button(type = ButtonType.submit, classes = if (entry.enabled) "danger" else "primary") { +(if (entry.enabled) "Hide this channel" else "Show this channel") }
            }
        }
    }
    panel("Details") {
        dl("kv") {
            view.row.station?.let { dt { +"Station key" }; dd { code { +it.key } } }
            dt { +"Guide" }
            dd { +(view.guideName ?: entry.guide?.let { "${it.guideId} / ${it.channelId}" } ?: "None mapped") }
            if (!entry.enabled) { dt { +"Why off" }; dd { +(view.row.station?.disabledReason ?: entry.autoDisabledReason ?: "hidden by hand") } }
            dt { +"Made by" }; dd { +(if (entry.createdBy == "policy") "The lineup policy" else "Hand") }
        }
        h3 { +"Sources, in failover order" }
        if (view.sources.isEmpty()) p("muted") { +"No source carries this channel right now." }
        else div("scroll") {
            table {
                caption("sr") { +"Sources, in failover order" }
                thead { tr { col("#", "num"); col("Provider"); col("Channel"); col("Protocol"); col("Health"); col("Actions") } }
                tbody {
                    view.sources.forEachIndexed { index, source ->
                        tr(chrome.flash?.takeIf { it.target == source.id }?.let { if (it.tone == Tone.BAD) "err-row" else "flash" }) {
                            td("num") { +(index + 1).toString() }
                            td { +source.provider }
                            td { +(source.channel ?: "") }
                            td { +source.protocol }
                            td {
                                val tone = when (source.status) { "HEALTHY" -> Tone.OK; "UNVERIFIED", "PROBATION" -> Tone.WARN; "WITHDRAWN", "ARCHIVED" -> Tone.OFF; else -> Tone.BAD }
                                chip(source.status.lowercase().replaceFirstChar { it.uppercase() }, tone)
                                if (source.disabled) { +" "; chip("Disabled by hand", Tone.OFF) }
                            }
                            td {
                                if (source.status == "WITHDRAWN") return@td
                                div("actions") {
                                    postForm("/admin/lineup/${entry.id}/sources/${source.id}/disable", chrome.session, keepScroll = true) {
                                        filterFields(view.filter)
                                        hiddenInput(name = "disabled") { value = (!source.disabled).toString() }
                                        button(type = ButtonType.submit, classes = "small") {
                                            attributes["aria-label"] = (if (source.disabled) "Enable" else "Disable") + " source ${index + 1} from ${source.provider}"
                                            +(if (source.disabled) "Enable" else "Disable")
                                        }
                                    }
                                    if (view.probes && !source.disabled && source.status != "ARCHIVED") postForm("/admin/lineup/${entry.id}/sources/${source.id}/probe", chrome.session, keepScroll = true) {
                                        filterFields(view.filter)
                                        button(type = ButtonType.submit, classes = "small quiet") { attributes["aria-label"] = "Probe source ${index + 1} from ${source.provider} now"; +"Probe now" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        p("muted small") {
            +"A disabled source is never selected or probed until it is enabled again; a session playing it moves to the next source. "
            +(if (view.probes) "Probe now puts the source at the front of the probe queue; its health updates within a minute or so." else "Probes are off on this server, so there is no Probe now.")
        }
    }
    view.disableConfirm?.let { source ->
        askDialog(chrome, "Disable source ${view.sources.indexOf(source) + 1} from ${source.provider}?", "/admin/lineup/${entry.id}/sources/${source.id}/disable", back, "Disable", danger = true) {
            filterFields(view.filter)
            hiddenInput(name = "disabled") { value = "true" }
            p { +"The channel stops using this source until you enable it again. Anyone watching it now moves to the next source, or loses the channel when this is the only one." }
        }
    }
}

internal fun goneStationPage(chrome: Chrome): String = layout("Channel not found", chrome, "/admin/lineup") {
    pageHead("Channel not found", "That channel is no longer in the lineup. A policy run may have merged or removed it.")
    a("/admin/lineup", classes = "btn") { +"Back to channels" }
}

private fun MAIN.noPolicy(what: String) {
    div("notice warn") { +"$what needs a lineup policy. Add policy.json to the data directory, or set SOFA_POLICY, and restart; without one the lineup is edited entry by entry through the admin API." }
}

internal fun mergesPage(chrome: Chrome, view: MergesView?, error: String? = null): String = layout("Merges", chrome, "/admin/merges") {
    pageHead("Merges", "Stations that are probably one channel under two names. Accepting writes an alias into the policy; dismissing or never merging keeps the pair apart for good. Each choice applies the policy.")
    if (view == null) { noPolicy("Merging stations"); return@layout }
    tabs(chrome, "/admin/merges", CHANNEL_TABS)
    notice(error?.let { "Not saved: $it" })
    val session = chrome.session
    fun FlowContent.pairForm(action: String, from: String, to: String, label: String, classes: String) = postForm(action, session, keepScroll = true) {
        hiddenInput(name = "revision") { value = view.revision }
        hiddenInput(name = "from") { value = from }
        hiddenInput(name = "to") { value = to }
        button(type = ButtonType.submit, classes = classes) { attributes["aria-label"] = "$label $from and $to"; +label }
    }
    panel("Suggested aliases", id = "suggested", aside = { span("muted") { +plural(view.suggestions.size, "suggestion") } }) {
        if (view.suggestions.isEmpty()) p("muted") { +"No suggestions. Runs suggest a pair when two live stations' names differ only by a trailing TV or Channel, or match loosely without a schedule to compare." }
        else div("scroll") {
            table {
                caption("sr") { +"Suggested aliases" }
                thead { tr { col("Station key"); col("Probably the same as"); col("Decide") } }
                tbody {
                    for ((from, to) in view.suggestions) tr {
                        td { code { +from } }
                        td { code { +to } }
                        td {
                            div("actions") {
                                pairForm("/admin/merges/accept", from, to, "Accept", "small primary")
                                pairForm("/admin/merges/dismiss", from, to, "Dismiss", "small")
                            }
                        }
                    }
                }
            }
        }
    }
    panel("Open findings", id = "findings", aside = { span("muted") { +plural(view.findings.size, "finding") } }) {
        if (view.findings.isEmpty()) p("muted") { +"No open findings." }
        else ul { for (finding in view.findings) li { +finding } }
    }
    if (view.unmapped.isNotEmpty()) div("notice warn") {
        +"${plural(view.unmapped.size, "category", "categories")} map to no group. "
        a("/admin/groups#unmapped") { +"Map them on Groups" }
    }
    div("cols") {
        panel("Learned aliases", aside = { span("muted") { +plural(view.learned.size, "alias", "aliases") } }) {
            if (view.learned.isEmpty()) p("muted") { +"No station has been merged by schedule." }
            else div("scroll") {
                table {
                    caption("sr") { +"Learned aliases" }
                    thead { tr { col("From"); col("Into"); col("Action", "sr") } }
                    tbody {
                        for ((from, to) in view.learned) tr { td { code { +from } }; td { code { +to } }; td { pairForm("/admin/merges/never", from, to, "Never merge", "small") } }
                    }
                }
            }
        }
        panel("Kept apart") {
            if (view.never.isEmpty()) p("muted") { +"No pair is kept apart." }
            else ul { for (pair in view.never) li { +pair.joinToString(" and ") } }
        }
    }
}

internal fun requiredPage(chrome: Chrome, view: RequiredView?): String = layout("Required", chrome, "/admin/required") {
    pageHead("Required channels", "Channels the policy keeps whatever happens to their sources. Off ones are listed first.")
    if (view == null) { noPolicy("Required channels"); return@layout }
    tabs(chrome, "/admin/required", CHANNEL_TABS)
    if (!view.ran) { div("panel") { p("muted") { +"No policy run has built the lineup yet." } }; return@layout }
    if (view.rows.isEmpty()) { div("panel") { p("muted") { +"No station is marked required in the policy. Set \"required\": true in a station override to keep it whatever its sources do." } }; return@layout }
    val off = view.rows.count { !it.station.enabled }
    div("tiles") {
        div("tile" + if (off > 0) " bad" else "") {
            span("label") { +"On" }
            span("value") { +thousands(view.rows.size - off); small { +" of ${thousands(view.rows.size)}" } }
        }
        div("tile" + if (view.rows.any { it.station.offeringIds.isEmpty() }) " bad" else "") {
            span("label") { +"With no live source" }
            span("value") { +thousands(view.rows.count { it.station.offeringIds.isEmpty() }) }
        }
    }
    div("panel tight") {
        div("scroll") {
            table {
                caption("sr") { +"Required channels" }
                thead { tr { col("No.", "num"); col("Station"); col("Pins"); col("Live sources", "num"); col("Health"); col("State") } }
                tbody {
                    for (row in view.rows.sortedWith(compareBy({ it.station.enabled }, { it.station.number.toDoubleOrNull() ?: Double.MAX_VALUE }))) tr {
                        val station = row.station
                        td("num") { +station.number }
                        td { a("/admin/lineup/${station.id}", classes = "rowlink") { +station.name }; br(); span("muted small mono") { +station.key } }
                        td("small") { if (row.pins.isEmpty()) span("muted") { +"none" } else { for (pin in row.pins) div { +listOfNotNull(pin.provider, pin.tvgId, pin.name).joinToString(" / ") } } }
                        td("num") { if (station.offeringIds.isEmpty()) chip("0", Tone.BAD) else +station.offeringIds.size.toString() }
                        td("small") { +row.health.entries.joinToString(", ") { "${it.value} ${it.key.lowercase()}" } }
                        td { if (station.enabled) chip("On", Tone.OK) else chip("Off · ${station.disabledReason ?: "hidden"}", Tone.BAD) }
                    }
                }
            }
        }
    }
}

internal fun providersPage(chrome: Chrome, view: ProvidersView, extra: (MAIN.() -> Unit)? = null): String = layout("Sources", chrome, "/admin/providers") {
    val now = chrome.now
    pageHead("Sources", "Where channels and guides come from, and whether their last import worked." + if (view.entries != null) " The policy takes channels from its provider entries in priority order." else "")
    extra?.invoke(this)
    panel("Channel providers", id = "imports", aside = { span("muted") { +"${view.rows.count { it.status.errorCode == null && it.status.lastSuccessAt != null }} of ${view.rows.size} importing" } }) {
        div("scroll") {
            table {
                caption("sr") { +"Channel providers" }
                thead { tr { col("Provider"); col("Kind"); col("Channels", "num"); col("Last import"); col("Status"); if (view.entries != null) col("Leads", "num") } }
                tbody {
                    for (row in view.rows.sortedWith(compareBy({ it.status.errorCode == null }, { it.status.id }))) tr {
                        val status = row.status
                        th { attributes["scope"] = "row"; +status.id }
                        td("muted") { +row.kind }
                        td("num") { +thousands(status.channels) }
                        td("nowrap") { ago(status.lastSuccessAt, now) }
                        td {
                            when {
                                status.errorCode != null -> chip(status.errorCode, Tone.BAD)
                                status.lastSuccessAt == null -> chip("Waiting", Tone.WARN)
                                else -> chip("OK", Tone.OK)
                            }
                            if (status.skippedEntries > 0) span("muted small") { +" ${thousands(status.skippedEntries)} skipped" }
                        }
                        if (view.entries != null) td("num") { +thousands(view.stationsLed[status.id] ?: 0) }
                    }
                }
            }
        }
    }
    sourceFilePanel(view.providerSummaries, view.guideSummaries)
    panel("Guides", id = "guides") {
        if (view.guides.isEmpty()) p("muted") { +"No guide sources are configured." }
        else div("scroll") {
            table {
                caption("sr") { +"Guides" }
                thead { tr { col("Guide"); col("Channels", "num"); col("Programmes", "num"); col("Last import"); col("Status") } }
                tbody {
                    for (guide in view.guides.sortedWith(compareBy({ it.errorCode == null }, { it.id }))) tr {
                        th { attributes["scope"] = "row"; +guide.id }
                        td("num") { +thousands(guide.channels) }
                        td("num") { +thousands(guide.programmes) }
                        td("nowrap") { ago(guide.lastSuccessAt, now) }
                        td { if (guide.errorCode != null) chip(guide.errorCode, Tone.BAD) else if (guide.lastSuccessAt == null) chip("Waiting", Tone.WARN) else chip("OK", Tone.OK) }
                    }
                }
            }
        }
    }
}

internal fun plexPage(chrome: Chrome, view: PlexView): String = layout("Plex", chrome, "/admin/plex") {
    pageHead("Plex and other players", "Plex reaches the lineup through emulated HDHomeRun tuners; other players take the M3U playlist and XMLTV guide.")
    tabs(chrome, "/admin/plex", PLAYER_TABS)
    panel("Plex sync", id = "sync") {
        val status = view.status
        if (status == null) { p("muted") { +"Off. Plex sync keeps Plex's channel maps in step with the lineup: set SOFA_PLEX_URL and SOFA_PLEX_TOKEN in .env and restart." }; return@panel }
        if (status.lastError != null) notice("The last sync failed: ${status.lastError}")
        dl("kv") {
            dt { +"Last sync" }
            dd { if (status.lastSyncAt == null) +"Not yet" else { ago(status.lastSyncAt, chrome.now); +" (${status.lastCause ?: "scheduled"})" } }
            dt { +"Runs" }; dd { +"After each lineup change and every ${status.syncMinutes} min" }
            if (status.missingDevices.isNotEmpty()) { dt { +"Not in Plex" }; dd { chip(status.missingDevices.joinToString(), Tone.BAD) } }
        }
        if (status.devices.isNotEmpty()) div("scroll mt-12") {
            table {
                caption("sr") { +"Tuners in Plex" }
                thead { tr { col("Tuner"); col("Device ID"); col("DVR"); col("Channels mapped", "num") } }
                tbody {
                    for (device in status.devices) tr {
                        th { attributes["scope"] = "row"; +"${device.device + 1}" }
                        td("mono") { +device.deviceId }
                        td { +device.dvr }
                        td("num") { +thousands(device.mapped); if (device.added) { +" "; chip("added", Tone.OK) } }
                    }
                }
            }
        }
        if (status.findings.isNotEmpty()) { h3 { +"Findings" }; ul { for (finding in status.findings) li { +finding } } }
    }
    panel("HDHomeRun tuners", id = "tuners") {
        if (!view.hdhrEnabled) { p("muted") { +"The HDHomeRun emulation is off (SOFA_HDHR_ENABLED=false)." }; return@panel }
        p {
            +"${thousands(view.enabledChannels)} channels on, across ${plural(view.devices.size, "device")} of up to ${view.tuners} streams each"
            if (view.devicesNeeded != null) {
                +"; the lineup needs ${view.devicesNeeded}. "
                if (view.devicesNeeded > view.devices.size) span("error-text") { +"Add ports to SOFA_HDHR_EXTRA_PORTS." }
            } else +"."
        }
        div("scroll") {
            table {
                caption("sr") { +"HDHomeRun devices" }
                thead { tr { col("Device"); col("Address"); col("Channels", "num") } }
                tbody { for (device in view.devices) tr { th { attributes["scope"] = "row"; +device.name }; td { code { +device.address } }; td("num") { +thousands(device.channels) } } }
            }
        }
        p("muted small") { +"Addresses use the host this page was reached by; extra devices listen on their own ports." }
    }
    panel("Player outputs", id = "outputs") {
        dl("kv") {
            dt { +"Playlist" }; dd { code { +view.playlist } }
            dt { +"Guide" }; dd { code { +view.guide } }
        }
        if (view.tokenConfigured && !view.revealed) div("mt-12") {
            postForm("/admin/plex/reveal", chrome.session) { button(type = ButtonType.submit) { +"Reveal the output token" } }
        }
        if (!view.tokenConfigured) p("muted") { +"Set SOFA_OUTPUT_TOKEN so players and Plex can fetch these URLs when the server takes tokens." }
        p("muted small") { +"Other players: add the playlist URL as an M3U source and the guide URL as its XMLTV source." }
    }
    panel("Set up Plex", id = "setup") {
        ol {
            li { +"Set SOFA_HDHR_ALLOW to the Plex server's address and SOFA_HDHR_TUNERS to the streams Plex may open at once, then restart." }
            li {
                +"In Plex Web, open Settings, Live TV & DVR, Set Up Plex DVR, and enter the device address by hand: "
                code { +(view.devices.firstOrNull()?.address ?: "SERVER_HOST:PORT") }
                +". Plex does not find the server on its own."
            }
            li { +"At the guide step, choose the XMLTV option and enter the guide URL above." }
            li { +"Map the channels; Plex pairs most by name and number. Add each extra device to the same DVR with its own address and the same guide URL." }
            li { +"After a lineup change, run Scan channels for the DVR in Plex, or let Plex sync do it." }
        }
    }
}
