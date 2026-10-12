package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.ActiveSession
import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.PolicyUnmappedCategory
import dev.jamesrich.sofaking.backend.api.ProviderStatus
import dev.jamesrich.sofaking.backend.api.SourcePin
import kotlinx.html.*
import kotlinx.html.stream.createHTML

internal class DashboardView(
    val providers: List<ProviderStatus>, val guides: List<GuideStatus>, val sessions: List<Pair<ActiveSession, String?>>,
    val stations: Int, val enabled: Int, val policy: Boolean, val unverified: Int?, val required: Int?, val findings: Int?,
)

internal class LineupView(
    val filter: LineupFilter, val rows: List<StationRow>, val matches: Int, val total: Int, val pages: Int, val groups: List<String>,
    /** The policy revision the row actions write against; null without a policy. */
    val revision: String?,
)

internal class RowError(val entryId: String, val message: String)

internal class MergesView(
    val revision: String, val learned: Map<String, String>, val never: List<List<String>>, val suggestions: List<Pair<String, String>>,
    val findings: List<String>, val unmapped: List<PolicyUnmappedCategory>,
)

internal class RequiredRow(val station: PolicyStation, val pins: List<SourcePin>, val health: Map<String, Int>)

internal class RequiredView(val rows: List<RequiredRow>, val ran: Boolean)

internal class HdhrDevice(val name: String, val address: String, val channels: Int)

internal class PlexView(
    val playlist: String, val guide: String, val tokenConfigured: Boolean, val revealed: Boolean, val hdhrEnabled: Boolean,
    val devices: List<HdhrDevice>, val devicesNeeded: Int?, val enabledChannels: Int, val tuners: Int, val status: List<AdminStatusLine>?,
)

private const val STYLE = """
:root{color-scheme:dark;--bg:#111418;--panel:#1a1f26;--line:#2c333d;--text:#e6e9ee;--muted:#9aa4b2;--accent:#7cc4ff;--bad:#ff8a80;--ok:#9be29b}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
header,main,footer{max-width:1200px;margin:0 auto;padding:0 16px}
header{display:flex;flex-wrap:wrap;gap:8px 16px;align-items:center;padding-top:12px;padding-bottom:12px;border-bottom:1px solid var(--line)}
header strong{margin-right:auto}
nav{display:flex;flex-wrap:wrap;gap:4px 14px}
a{color:var(--accent)}
nav a{text-decoration:none}nav a[aria-current]{text-decoration:underline}
h1{font-size:1.4rem;margin:18px 0 8px}h2{font-size:1.1rem;margin:22px 0 8px}
table{width:100%;border-collapse:collapse;margin:8px 0}
.scroll{overflow-x:auto}
th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:600}
input,select,button{font:inherit;color:var(--text);background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:6px 8px}
button{cursor:pointer;background:#24303d}button:hover{border-color:var(--accent)}
form.inline{display:inline}
.filters{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:8px 0}
.edit{display:flex;flex-wrap:wrap;gap:6px;margin-top:6px}.edit input{width:9em}
.muted{color:var(--muted)}.bad{color:var(--bad)}.ok{color:var(--ok)}
.notice{background:var(--panel);border:1px solid var(--line);border-left:4px solid var(--bad);padding:8px 12px;margin:12px 0}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;margin:8px 0}
.card{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:10px}
.card b{display:block;font-size:1.4rem}
code{word-break:break-all;background:var(--panel);padding:1px 4px;border-radius:4px}
footer{color:var(--muted);padding-top:24px;padding-bottom:24px}
"""

private const val HTMX_CONFIG =
    """{"allowEval":false,"allowScriptTags":false,"includeIndicatorStyles":false,"historyCacheSize":0,"refreshOnHistoryMiss":true,""" +
        """"responseHandling":[{"code":"204","swap":false},{"code":"[23]..","swap":true},{"code":"4..","swap":true,"error":false},{"code":"...","swap":false,"error":true}]}"""

private val NAV = listOf("/admin" to "Dashboard", "/admin/lineup" to "Lineup", "/admin/merges" to "Merges", "/admin/required" to "Required", "/admin/plex" to "Plex & players")

/** The shared page: a nav and the sign-out form for a [session], htmx boosting its links and forms. */
private fun layout(title: String, session: AdminSession?, current: String?, content: MAIN.() -> Unit): String = "<!DOCTYPE html>\n" + createHTML(prettyPrint = false).html {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        meta(name = "htmx-config", content = HTMX_CONFIG)
        title { +"$title - Sofa King admin" }
        style { unsafe { +STYLE } }
        if (session != null) script(src = "/admin/static/htmx.min.js") {}
    }
    body {
        if (session != null) {
            attributes["hx-boost"] = "true"
            attributes["hx-headers"] = """{"X-CSRF-Token":"${session.csrf}"}"""
        }
        header {
            strong { +"Sofa King admin" }
            if (session != null) {
                nav {
                    for ((href, label) in NAV) a(href) { if (href == current) attributes["aria-current"] = "page"; +label }
                }
                postForm("/admin/logout", session) { button(type = ButtonType.submit) { +"Sign out" } }
            }
        }
        main { content() }
        footer { +"Sofa King well organized." }
    }
}

/** A POST form with the CSRF token; [keepScroll] keeps the page where it is when htmx swaps the answer in. */
private fun FlowContent.postForm(action: String, session: AdminSession, keepScroll: Boolean = false, block: FORM.() -> Unit) = form(action = action, method = FormMethod.post, classes = "inline") {
    // The stream writes a tag's attributes with its first child, so they are set before any.
    if (keepScroll) attributes["hx-swap"] = "innerHTML show:none"
    hiddenInput(name = "csrf") { value = session.csrf }
    block()
}

private fun FlowContent.notice(message: String?) {
    if (message != null) div("notice") { attributes["role"] = "alert"; +message }
}

private fun TR.cell(value: Any?) = td { +(value?.toString() ?: "") }

internal fun signInPage(enabled: Boolean, error: String? = null): String = layout("Sign in", null, null) {
    h1 { +"Sign in" }
    if (!enabled) {
        p { +"The portal needs a token to sign in with. Set SOFA_ADMIN_TOKEN (or SOFA_API_TOKEN) and restart the server." }
        return@layout
    }
    notice(error)
    form(action = "/admin/login", method = FormMethod.post) {
        p {
            label { htmlFor = "token"; +"Admin token" }
            br()
            passwordInput(name = "token") { id = "token"; required = true; attributes["autocomplete"] = "current-password"; size = "40" }
        }
        button(type = ButtonType.submit) { +"Sign in" }
    }
    p("muted") { +"The admin token is SOFA_ADMIN_TOKEN, or SOFA_API_TOKEN when no admin token is set." }
}

internal fun dashboardPage(session: AdminSession, view: DashboardView): String = layout("Dashboard", session, "/admin") {
    h1 { +"Dashboard" }
    div("cards") {
        fun card(label: String, value: Any, href: String? = null) = div("card") {
            b { if (href != null) a(href) { +value.toString() } else +value.toString() }
            span("muted") { +label }
        }
        card("stations", view.stations, "/admin/lineup")
        card("enabled", view.enabled, "/admin/lineup?state=enabled")
        if (view.unverified != null) card("unverified", view.unverified, "/admin/lineup?state=unverified")
        if (view.required != null) card("required", view.required, "/admin/required")
        if (view.policy) card("findings", view.findings ?: "not run yet", "/admin/merges")
        card("sessions", view.sessions.size)
    }
    h2 { +"Providers" }
    div("scroll") {
        table {
            thead { tr { th { +"Provider" }; th { +"Channels" }; th { +"Last import" }; th { +"Error" } } }
            tbody {
                for (provider in view.providers) tr {
                    cell(provider.id); cell(provider.channels); cell(provider.lastSuccessAt ?: "never")
                    td { if (provider.errorCode != null) span("bad") { +provider.errorCode!! } }
                }
            }
        }
    }
    h2 { +"Guides" }
    if (view.guides.isEmpty()) p("muted") { +"No guide sources are configured." }
    else div("scroll") {
        table {
            thead { tr { th { +"Guide" }; th { +"Channels" }; th { +"Programmes" }; th { +"Last import" }; th { +"Error" } } }
            tbody {
                for (guide in view.guides) tr {
                    cell(guide.id); cell(guide.channels); cell(guide.programmes); cell(guide.lastSuccessAt ?: "never")
                    td { if (guide.errorCode != null) span("bad") { +guide.errorCode!! } }
                }
            }
        }
    }
    h2 { +"Playback sessions" }
    if (view.sessions.isEmpty()) p("muted") { +"Nobody is watching." }
    else div("scroll") {
        table {
            thead { tr { th { +"Channel" }; th { +"Provider" }; th { +"Intent" }; th { +"Player" }; th { +"Expires" } } }
            tbody {
                for ((active, name) in view.sessions) tr {
                    cell(name ?: active.channelId); cell(active.providerId); cell(active.intent + if (active.busy) ", busy" else "")
                    cell(if (active.external) "external" else "app"); cell(active.expiresAt)
                }
            }
        }
    }
}

internal fun lineupPage(session: AdminSession, view: LineupView, error: RowError? = null): String = layout("Lineup", session, "/admin/lineup") {
    val filter = view.filter
    h1 { +"Lineup" }
    notice(error?.let { "Not saved: ${it.message}" })
    form(action = "/admin/lineup", method = FormMethod.get, classes = "filters") {
        attributes["hx-trigger"] = "submit, change, input delay:400ms"
        searchInput(name = "q") { id = "q"; value = filter.q; placeholder = "Name, number or key"; attributes["aria-label"] = "Search" }
        select {
            name = "group"; attributes["aria-label"] = "Group"
            option { value = ""; +"All groups" }
            for (group in view.groups) option { value = group; selected = group == filter.group; +group }
        }
        select {
            name = "state"; attributes["aria-label"] = "State"
            val states = listOf("" to "Any state", "enabled" to "Enabled", "hidden" to "Hidden") + if (view.revision != null) listOf("unverified" to "Unverified") else emptyList()
            for ((value, label) in states) option { this.value = value; selected = value == filter.state; +label }
        }
        if (view.revision != null) label {
            checkBoxInput(name = "required") { value = "true"; checked = filter.required }
            +" Required only"
        }
        button(type = ButtonType.submit) { +"Filter" }
    }
    p("muted") { +("${view.matches} of ${view.total} stations" + if (view.pages > 1) ", page ${filter.page} of ${view.pages}" else "") }
    div("scroll") {
        table {
            thead { tr { th { +"Number" }; th { +"Name" }; th { +"Group" }; th { +"Sources" }; th { +"Guide" }; th { +"State" }; th { +"Actions" } } }
            tbody {
                for (row in view.rows) tr {
                    val entry = row.entry
                    id = "row-${entry.id}"
                    cell(entry.number)
                    td {
                        +entry.name
                        if (row.required) span("muted") { +" required" }
                        if (error?.entryId == entry.id) div("bad") { +error.message }
                    }
                    cell(entry.group ?: "")
                    cell(entry.offeringIds.size)
                    cell(row.guideStatus)
                    td { if (entry.enabled) span("ok") { +row.state } else span("muted") { +row.state } }
                    td {
                        rowForm("/admin/lineup/${entry.id}/visibility", session, view) {
                            hiddenInput(name = "enabled") { value = (!entry.enabled).toString() }
                            button(type = ButtonType.submit) { +(if (entry.enabled) "Hide" else "Show") }
                        }
                        details {
                            summary { +"Edit" }
                            rowForm("/admin/lineup/${entry.id}/edit", session, view, classes = "edit") {
                                textInput(name = "name") { value = entry.name; attributes["aria-label"] = "Name" }
                                textInput(name = "number") { value = entry.number; attributes["aria-label"] = "Number"; size = "6" }
                                textInput(name = "group") { value = entry.group ?: ""; attributes["aria-label"] = "Group"; placeholder = "Group" }
                                button(type = ButtonType.submit) { +"Save" }
                            }
                        }
                    }
                }
            }
        }
    }
    if (view.pages > 1) p {
        if (filter.page > 1) a("/admin/lineup?" + filter.query(filter.page - 1)) { +"Previous" }
        +" "
        if (filter.page < view.pages) a("/admin/lineup?" + filter.query(filter.page + 1)) { +"Next" }
    }
}

/** A row action: the CSRF token, the policy revision and the filters, so the page comes back as it was. */
private fun FlowContent.rowForm(action: String, session: AdminSession, view: LineupView, classes: String = "inline", block: FORM.() -> Unit) =
    form(action = action, method = FormMethod.post, classes = classes) {
        attributes["hx-swap"] = "innerHTML show:none"
        hiddenInput(name = "csrf") { value = session.csrf }
        view.revision?.let { revision -> hiddenInput(name = "revision") { value = revision } }
        val filter = view.filter
        for ((name, value) in listOf("q" to filter.q, "group" to filter.group, "state" to filter.state, "page" to filter.page.toString())) {
            hiddenInput(name = name) { this.value = value }
        }
        if (filter.required) hiddenInput(name = "required") { value = "true" }
        block()
    }

private fun MAIN.noPolicy(what: String) {
    p { +"$what needs a lineup policy. Set SOFA_POLICY to a policy file and restart; without one the lineup is curated through the lineup and curation API." }
}

internal fun mergesPage(session: AdminSession, view: MergesView?, error: String? = null): String = layout("Merges & findings", session, "/admin/merges") {
    h1 { +"Merges & findings" }
    if (view == null) { noPolicy("Merging stations"); return@layout }
    notice(error?.let { "Not saved: $it" })
    fun FlowContent.pairForm(action: String, from: String, to: String, label: String) = postForm(action, session, keepScroll = true) {
        hiddenInput(name = "revision") { value = view.revision }
        hiddenInput(name = "from") { value = from }
        hiddenInput(name = "to") { value = to }
        button(type = ButtonType.submit) { +label }
    }
    h2 { +"Learned aliases" }
    if (view.learned.isEmpty()) p("muted") { +"No station has been merged by schedule." }
    else div("scroll") {
        table {
            thead { tr { th { +"From" }; th { +"Into" }; th { } } }
            tbody {
                for ((from, to) in view.learned) tr { cell(from); cell(to); td { pairForm("/admin/merges/never", from, to, "Never merge") } }
            }
        }
    }
    h2 { +"Suggested aliases" }
    if (view.suggestions.isEmpty()) p("muted") { +"No suggestions." }
    else div("scroll") {
        table {
            thead { tr { th { +"Key" }; th { +"Possible alias of" }; th { } } }
            tbody {
                for ((from, to) in view.suggestions) tr {
                    cell(from); cell(to)
                    td {
                        pairForm("/admin/merges/accept", from, to, "Accept")
                        +" "
                        pairForm("/admin/merges/dismiss", from, to, "Dismiss")
                    }
                }
            }
        }
    }
    if (view.never.isNotEmpty()) {
        h2 { +"Never merged" }
        ul { for (pair in view.never) li { +pair.joinToString(" and ") } }
    }
    h2 { +"Findings" }
    if (view.findings.isEmpty()) p("muted") { +"No open findings." }
    else ul { for (finding in view.findings) li { +finding } }
    h2 { +"Unmapped categories" }
    if (view.unmapped.isEmpty()) p("muted") { +"Every category is mapped to a group." }
    else div("scroll") {
        table {
            thead { tr { th { +"Provider" }; th { +"Category" }; th { +"Stations" }; th { +"Example" } } }
            tbody { for (row in view.unmapped) tr { cell(row.provider); cell(row.category); cell(row.stations); cell(row.example) } }
        }
        p("muted") { +"Add a category to groups.map in the policy file so its sources vote for a group, or to groups.weak when it names no genre." }
    }
}

internal fun requiredPage(session: AdminSession, view: RequiredView?): String = layout("Required", session, "/admin/required") {
    h1 { +"Required channels" }
    if (view == null) { noPolicy("Required channels"); return@layout }
    if (!view.ran) { p("muted") { +"No policy run has built the lineup yet." }; return@layout }
    if (view.rows.isEmpty()) { p("muted") { +"No station is marked required in the policy." }; return@layout }
    div("scroll") {
        table {
            thead { tr { th { +"Number" }; th { +"Station" }; th { +"Pins" }; th { +"Live sources" }; th { +"Health" }; th { +"State" } } }
            tbody {
                for (row in view.rows) tr {
                    val station = row.station
                    cell(station.number)
                    td { +station.name; br(); span("muted") { +station.key } }
                    td { for (pin in row.pins) div { +listOfNotNull(pin.provider, pin.tvgId, pin.name).joinToString(" / ") } }
                    td { if (station.offeringIds.isEmpty()) span("bad") { +"0" } else +station.offeringIds.size.toString() }
                    cell(row.health.entries.joinToString(", ") { "${it.value} ${it.key.lowercase()}" })
                    td { if (station.enabled) span("ok") { +"on" } else span("bad") { +"off: ${station.disabledReason ?: "hidden"}" } }
                }
            }
        }
    }
}

internal fun plexPage(session: AdminSession, view: PlexView): String = layout("Plex & players", session, "/admin/plex") {
    h1 { +"Plex & players" }
    h2 { +"Player outputs" }
    section {
        id = "outputs"
        p { +"Playlist: "; code { +view.playlist } }
        p { +"Guide: "; code { +view.guide } }
        if (view.tokenConfigured && !view.revealed) postForm("/admin/plex/reveal", session) { button(type = ButtonType.submit) { +"Reveal the output token" } }
        if (!view.tokenConfigured) p("muted") { +"Set SOFA_OUTPUT_TOKEN so players and Plex can fetch these URLs when the server takes tokens." }
    }
    h2 { +"HDHomeRun devices" }
    if (!view.hdhrEnabled) p("muted") { +"The HDHomeRun emulation is off (SOFA_HDHR_ENABLED=false)." }
    else {
        div("scroll") {
            table {
                thead { tr { th { +"Device" }; th { +"Address" }; th { +"Channels" } } }
                tbody { for (device in view.devices) tr { cell(device.name); td { code { +device.address } }; cell(device.channels) } }
            }
        }
        p {
            +"${view.enabledChannels} enabled channels, ${view.devices.size} devices configured, ${view.tuners} tuners each"
            if (view.devicesNeeded != null) {
                +", ${view.devicesNeeded} needed. "
                if (view.devicesNeeded > view.devices.size) span("bad") { +"Add ports to SOFA_HDHR_EXTRA_PORTS." }
            }
        }
        p("muted") { +"Addresses use the host this page was reached by; extra devices listen on their own ports." }
    }
    if (view.status != null) {
        h2 { +"Plex sync" }
        if (view.status.isEmpty()) p("muted") { +"No status yet." }
        else table { tbody { for (line in view.status) tr { th { +line.label }; cell(line.value) } } }
    }
    h2 { +"Set up Plex" }
    ol {
        li { +"Set SOFA_HDHR_ALLOW to the Plex server's address and SOFA_HDHR_TUNERS to the streams Plex may open at once, then restart." }
        li {
            +"In Plex Web, open Settings, Live TV & DVR, Set Up Plex DVR, and enter the device address by hand: "
            code { +(view.devices.firstOrNull()?.address ?: "SERVER_HOST:PORT") }
            +". Plex does not find the server on its own."
        }
        li { +"At the guide step, choose the XMLTV option and enter the guide URL above." }
        li { +"Map the channels; Plex pairs most by name and number. Add each extra device to the same DVR with its own address and the same guide URL." }
        li { +"After a lineup change, run Scan channels for the DVR in Plex." }
    }
    h2 { +"Other players" }
    p { +"Add the playlist URL as an M3U source and the guide URL as its XMLTV source." }
}
