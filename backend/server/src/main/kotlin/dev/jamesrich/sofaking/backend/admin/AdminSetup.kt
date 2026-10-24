package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import dev.jamesrich.sofaking.backend.setup.ServiceCatalog
import dev.jamesrich.sofaking.backend.setup.ServiceInfo
import dev.jamesrich.sofaking.backend.setup.SetupService
import io.ktor.http.Parameters
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.address
import kotlinx.html.aside
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.checkBoxInput
import kotlinx.html.classes
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.map
import kotlinx.html.ol
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.urlInput

/**
 * One native service on the Sources page: the catalog's description, whether the source file has
 * it, and its options as written.
 */
internal class ServiceRow(val info: ServiceInfo, val on: Boolean, val options: Map<String, String>)

/** A change the Save confirmation lists. */
internal class SetupConfirm(
    val title: String,
    val action: String,
    val lines: List<String>,
    val fields: Map<String, List<String>>,
)

/**
 * The setup half of the Sources page: every native service as a switch with its options, the M3U playlists with an
 * add form, and, after a Save, the confirmation that names what changes and that the server restarts. [errors] are a
 * refused save's reasons, [proposed] its form, so the page keeps what was typed.
 */
internal class SetupView(
    val services: List<ServiceRow>,
    val playlists: List<FeedConfig>,
    val hasSources: Boolean,
    val path: String,
    val errors: List<String> = emptyList(),
    val proposed: Parameters? = null,
    val confirm: SetupConfirm? = null,
    /** Whether the services save can build the starter lineup too: there is no lineup policy yet. */
    val canBuildStarter: Boolean = false,
) {
    constructor(
        view: SetupView,
        errors: List<String> = emptyList(),
        proposed: Parameters? = null,
        confirm: SetupConfirm? = null,
    ) :
        this(view.services, view.playlists, view.hasSources, view.path, errors, proposed, confirm, view.canBuildStarter)

    companion object {
        /**
         * The view of [document], the source file as written; with no file, every service that works
         * without options starts checked.
         */
        fun of(document: SourceDocument, setup: SetupService): SetupView {
            val native = document.providers.filter { it.native }.groupBy { it.service!! }
            val fresh = !setup.hasSources && document.providers.isEmpty()
            val services = ServiceCatalog.all.map { info ->
                val configured = native[info.service]?.firstOrNull()
                ServiceRow(info, configured != null || (fresh && info.startsOn), configured?.options.orEmpty())
            }
            return SetupView(
                services,
                document.providers.filter {
                    !it.native
                },
                setup.hasSources,
                setup.sourcesPath.toString(),
                canBuildStarter = setup.canWriteStarterPolicy,
            )
        }
    }
}

private fun FlowContent.restartNote() = p("muted small") {
    +(
        "Saving writes the source file and restarts the server, which takes a few seconds. Anyone watching loses the " +
            "stream and can open the channel again; you stay signed in."
        )
}

/** The services as switches, with the options the checked ones take, and one Save. */
internal fun FlowContent.servicesPanel(chrome: Chrome, view: SetupView) = panel("Services", id = "services", aside = {
    span("muted") { +"${view.services.count { it.on }} of ${view.services.size} on" }
}) {
    p("muted small") {
        +(
            if (view.hasSources) {
                "Every free service the server can read. Tick the ones you want; each brings its channels and guide."
            } else {
                "No source file yet. Tick the services you want and save; the server then imports their channels and " +
                    "guides."
            }
            )
    }
    if (view.errors.isNotEmpty()) notice("Not saved:", view.errors)
    form(action = "/admin/sources/services", method = FormMethod.post, classes = "stack") {
        id = "services-form"
        hiddenInput(name = "csrf") { value = chrome.session.csrf }
        ul("services") {
            for (row in view.services) {
                serviceItem(view, row)
            }
        }
        if (view.canBuildStarter) {
            starterSwitch(view)
        }
        restartNote()
        div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Save and restart" } }
    }
}

private fun UL.serviceItem(view: SetupView, row: ServiceRow) = li {
    val info = row.info
    val checked = view.proposed?.let { info.service in it.getAll(SERVICE_FIELD).orEmpty() } ?: row.on
    label("switch") {
        checkBoxInput(name = SERVICE_FIELD) {
            value = info.service
            this.checked = checked
        }
        span("name") { +info.label }
        span("muted small") { +info.carries }
    }
    if (info.options.isNotEmpty()) {
        serviceOptions(view, row)
    }
}

private fun FlowContent.serviceOptions(view: SetupView, row: ServiceRow) = div("options") {
    val info = row.info
    for (option in info.options) {
        div("field") {
            val id = "opt-${info.service}-${option.key}"
            label {
                htmlFor = id
                +option.label
                if (option.required) +" (required)"
            }
            textInput(name = optionField(info.service, option.key)) {
                this.id = id
                value = view.proposed?.get(optionField(info.service, option.key))
                    ?: row.options[option.key].orEmpty()
                placeholder = option.hint.substringBefore(';').substringBefore(',')
            }
            span("hint") { +option.hint }
        }
    }
}

private fun FlowContent.starterSwitch(view: SetupView) = label("switch") {
    checkBoxInput(name = STARTER_FIELD) {
        value = "on"
        checked = view.proposed?.wantsStarter() ?: true
    }
    span("name") { +"Build the starter lineup too" }
    span("muted small") {
        +(
            "One station per channel across these services, grouped and numbered in blocks; channels " +
                "come on as the server verifies them. Untick to write a lineup policy yourself."
            )
    }
}

/** The M3U playlists of the source file, each with Remove, and the form that adds one. */
internal fun FlowContent.playlistsPanel(chrome: Chrome, view: SetupView) = panel("M3U playlists", id = "playlists") {
    if (view.playlists.isEmpty()) {
        p("muted") { +"No M3U playlist. Add one for a provider the server has no built-in client for." }
    } else {
        playlistTable(chrome, view.playlists)
    }
    addPlaylist(chrome, view)
}

private fun FlowContent.playlistTable(chrome: Chrome, playlists: List<FeedConfig>) = div("scroll") {
    table {
        caption("sr") { +"M3U playlists" }
        thead {
            tr {
                col("ID")
                col("Playlist")
                col("Guide")
                col("Action", "sr")
            }
        }
        tbody {
            for (playlist in playlists) {
                tr {
                    th {
                        attributes["scope"] = "row"
                        +playlist.id
                    }
                    td("muted") { +hostOnly(playlist.playlistUrl) }
                    td("muted") { +(playlist.guideUrl?.let(::hostOnly) ?: "none") }
                    td("num") {
                        postForm("/admin/sources/remove", chrome.session) {
                            hiddenInput(name = "id") { value = playlist.id }
                            button(type = ButtonType.submit, classes = "small quiet") { +"Remove" }
                        }
                    }
                }
            }
        }
    }
}

private fun FlowContent.addPlaylist(chrome: Chrome, view: SetupView) = details {
    summary { +"Add an M3U playlist" }
    if (view.errors.isNotEmpty() && view.proposed?.contains("url") == true) notice("Not added:", view.errors)
    form(action = "/admin/sources/playlist", method = FormMethod.post, classes = "stack") {
        hiddenInput(name = "csrf") { value = chrome.session.csrf }
        div("runtime") {
            div("field") {
                label {
                    htmlFor = "pl-id"
                    +"ID"
                }
                textInput(name = "id") {
                    id = "pl-id"
                    value = view.proposed?.get("id") ?: ""
                    required = true
                    pattern = "[a-zA-Z0-9_-]{1,64}"
                }
                span("hint") { +"A short name, such as iptv; it names the provider everywhere" }
            }
            div("field") {
                label {
                    htmlFor = "pl-url"
                    +"Playlist URL"
                }
                urlInput(name = "url") {
                    id = "pl-url"
                    value = view.proposed?.get("url") ?: ""
                    required = true
                }
                span("hint") { +"The .m3u or .m3u8 address; a token in it stays in the file and never shows here" }
            }
            div("field") {
                label {
                    htmlFor = "pl-guide"
                    +"Guide URL"
                }
                urlInput(name = "guide") {
                    id = "pl-guide"
                    value = view.proposed?.get("guide") ?: ""
                }
                span("hint") { +"Its XMLTV, plain or gzipped; leave empty for none" }
            }
        }
        restartNote()
        div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Add and restart" } }
    }
}

/** The confirmation of a setup write: what changes, then the restart. */
internal fun FlowContent.setupConfirm(chrome: Chrome, confirm: SetupConfirm, back: String) =
    askDialog(chrome, confirm.title, confirm.action, back, "Save and restart") {
        for ((name, values) in confirm.fields) for (value in values) hiddenInput(name = name) { this.value = value }
        if (confirm.lines.isNotEmpty()) ul { for (line in confirm.lines) li { +line } }
        p {
            +(
                "The server writes the file and restarts; it is back in a few seconds, and you stay signed in. " +
                    "Anyone watching loses the stream until then."
                )
        }
    }

/** The home page's setup steps, shown while the server has no source file, or no lineup policy and no lineup. */
internal fun FlowContent.setupCards(chrome: Chrome, setup: SetupService, providers: Int) =
    panel("Set up", id = "setup") {
        ol("setup") {
            li(if (setup.hasSources) "done" else null) {
                b { +"Add your sources" }
                if (setup.hasSources) {
                    span("muted") { +" ${plural(providers, "provider")} configured." }
                } else {
                    span("muted") {
                        +" Tick the free services you want; no account is needed. Saving builds the lineup too."
                    }
                    div("actions") { a("/admin/providers#services", classes = "btn primary") { +"Choose services" } }
                }
            }
            li(if (setup.hasPolicy) "done" else null) {
                b { +"Build the lineup" }
                if (setup.hasPolicy) {
                    span("muted") { +" The lineup policy runs after every import." }
                } else {
                    span("muted") {
                        +(
                            " One station per channel across every service, grouped and numbered in blocks; channels " +
                                "come on as the server verifies them."
                            )
                    }
                    if (setup.hasSources) {
                        div("actions") {
                            postForm("/admin/policy/starter", chrome.session) {
                                button(type = ButtonType.submit, classes = "primary") { +"Build the lineup" }
                            }
                        }
                    } else {
                        span("muted small") { +" Add sources first." }
                    }
                }
            }
        }
    }

/**
 * The page a setup write answers: the server is going down to read what it wrote. The browser
 * returns to the portal on its own.
 */
internal fun restartingPage(theme: String?, what: String): String =
    layout("Restarting", null, null, theme, refreshSeconds = 20) {
        div("panel signin") {
            h1("mt-0") { +"Restarting" }
            p { +"$what Hang tight: the server is restarting to read it and is back in a few seconds." }
            p("muted") {
                +(
                    "This page opens the portal again in 20 seconds, still signed in. If the server does not come " +
                        "back, its log says why, and the file it wrote is at the path the Sources page showed."
                    )
            }
            a("/admin", classes = "btn primary") { +"Open the portal" }
        }
    }
