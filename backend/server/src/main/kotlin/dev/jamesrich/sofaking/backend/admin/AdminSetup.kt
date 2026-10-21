package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.SourceDocument
import dev.jamesrich.sofaking.backend.catalog.httpUri
import dev.jamesrich.sofaking.backend.setup.ServiceCatalog
import dev.jamesrich.sofaking.backend.setup.ServiceInfo
import dev.jamesrich.sofaking.backend.setup.SetupService
import io.ktor.http.Parameters
import kotlinx.html.*

/** One native service on the Sources page: the catalog's description, whether the source file has it, and its options as written. */
internal class ServiceRow(val info: ServiceInfo, val on: Boolean, val options: Map<String, String>)

/** A change the Save confirmation lists. */
internal class SetupConfirm(val title: String, val action: String, val lines: List<String>, val fields: Map<String, List<String>>)

/**
 * The setup half of the Sources page: every native service as a switch with its options, the M3U playlists with an
 * add form, and, after a Save, the confirmation that names what changes and that the server restarts. [errors] are a
 * refused save's reasons, [proposed] its form, so the page keeps what was typed.
 */
internal class SetupView(
    val services: List<ServiceRow>, val playlists: List<FeedConfig>, val hasSources: Boolean, val path: String,
    val errors: List<String> = emptyList(), val proposed: Parameters? = null, val confirm: SetupConfirm? = null,
    /** Whether the services save can build the starter lineup too: there is no lineup policy yet. */
    val canBuildStarter: Boolean = false,
) {
    constructor(view: SetupView, errors: List<String> = emptyList(), proposed: Parameters? = null, confirm: SetupConfirm? = null) :
        this(view.services, view.playlists, view.hasSources, view.path, errors, proposed, confirm, view.canBuildStarter)

    companion object {
        /** The view of [document], the source file as written; with no file, every service that works without options starts checked. */
        fun of(document: SourceDocument, setup: SetupService): SetupView {
            val native = document.providers.filter { it.native }.groupBy { it.service!! }
            val fresh = !setup.hasSources && document.providers.isEmpty()
            val services = ServiceCatalog.all.map { info ->
                val configured = native[info.service]?.firstOrNull()
                ServiceRow(info, configured != null || (fresh && info.startsOn), configured?.options.orEmpty())
            }
            return SetupView(services, document.providers.filter { !it.native }, setup.hasSources, setup.sourcesPath.toString(), canBuildStarter = setup.canWriteStarterPolicy)
        }
    }
}

/** The form's fields, less the session's, as hidden inputs of the confirmation carry them. */
internal fun Parameters.setupFields(): Map<String, List<String>> = names().filter { it != "csrf" && it != "confirm" }.associateWith { getAll(it).orEmpty() }

/** The host of [url], or a stand-in, for a page that must not show a path or query. */
internal fun hostOnly(url: String?): String = url?.trim()?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "a URL"

/** The services form's checkbox that builds the starter lineup in the same save. */
internal const val STARTER_FIELD = "starter"

/** Whether the services form asks for the starter lineup too. */
internal fun Parameters.wantsStarter(): Boolean = this[STARTER_FIELD] == "on"

/** The form field that switches a service on, and the one that holds one of its options. */
internal fun serviceField(service: String) = "svc"
internal fun optionField(service: String, key: String) = "opt.$service.$key"

/**
 * [document] with the services form applied: a checked service keeps its providers (the first takes the form's
 * options), an unchecked one loses them, and a checked service with none gets one named after it. Playlists and guides
 * stay as they are. Fails with every option that does not fit.
 */
internal fun applyServicesForm(document: SourceDocument, form: Parameters): Result<SourceDocument> {
    val checked = form.getAll(serviceField("")).orEmpty().toSet()
    val errors = ArrayList<String>()
    val providers = ArrayList<FeedConfig>()
    val taken = document.providers.mapTo(HashSet()) { it.id }
    for (info in ServiceCatalog.all) {
        val existing = document.providers.filter { it.native && it.service == info.service }
        if (info.service !in checked) continue
        val options = LinkedHashMap(existing.firstOrNull()?.options.orEmpty())
        for (option in info.options) {
            val value = form[optionField(info.service, option.key)]?.trim().orEmpty()
            when {
                value.isEmpty() && option.required -> errors += "${info.label}: ${option.label} is required"
                value.isEmpty() -> options.remove(option.key)
                option.pattern != null && !option.pattern.matches(value) -> errors += "${info.label}: ${option.label} does not look right (${option.hint})"
                else -> options[option.key] = value
            }
        }
        if (existing.isEmpty()) {
            val id = generateSequence(info.service) { "$it-2" }.first { it !in taken }
            providers += FeedConfig(id, type = "native", service = info.service, options = options)
        } else {
            providers += existing.first().copy(options = options)
            providers += existing.drop(1)
        }
    }
    if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString("\n")))
    // Keep the file's order for the providers that stay; new ones go at the end.
    val kept = document.providers.mapNotNull { original -> providers.firstOrNull { it.id == original.id } ?: original.takeIf { !it.native } }
    val added = providers.filter { p -> document.providers.none { it.id == p.id } }
    return Result.success(document.copy(providers = kept + added))
}

/** [document] with an M3U playlist added from the add form: `id`, `url` and an optional `guide`. */
internal fun applyPlaylistForm(document: SourceDocument, form: Parameters): Result<SourceDocument> {
    val id = form["id"]?.trim().orEmpty()
    val url = form["url"]?.trim().orEmpty()
    val guide = form["guide"]?.trim()?.takeIf { it.isNotEmpty() }
    val errors = ArrayList<String>()
    if (!id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) errors += "The ID is letters, digits, - and _, up to 64 characters"
    else if (document.providers.any { it.id == id }) errors += "A provider named $id already exists"
    if (runCatching { httpUri(url) }.isFailure) errors += "The playlist URL must be an http or https address"
    if (guide != null && runCatching { httpUri(guide) }.isFailure) errors += "The guide URL must be an http or https address"
    if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString("\n")))
    return Result.success(document.copy(providers = document.providers + FeedConfig(id, playlistUrl = url, guideUrl = guide)))
}

/** What the services form changes against [document], as the confirmation lists it. */
internal fun servicesChanges(document: SourceDocument, next: SourceDocument): List<String> {
    fun services(d: SourceDocument) = d.providers.filter { it.native }.map { it.service!! }.toSet()
    val before = services(document)
    val after = services(next)
    fun label(service: String) = ServiceCatalog.byService(service)?.label ?: service
    val lines = ArrayList<String>()
    (after - before).sorted().takeIf { it.isNotEmpty() }?.let { lines += "Turns on ${it.joinToString { s -> label(s) }}" }
    (before - after).sorted().takeIf { it.isNotEmpty() }?.let { lines += "Turns off ${it.joinToString { s -> label(s) }}; their channels leave the lineup's stations as sources" }
    val changedOptions = (before intersect after).filter { service ->
        document.providers.first { it.native && it.service == service }.options != next.providers.first { it.native && it.service == service }.options
    }
    if (changedOptions.isNotEmpty()) lines += "Changes the options of ${changedOptions.joinToString { label(it) }}"
    return lines
}

private fun FlowContent.restartNote() = p("muted small") {
    +"Saving writes the source file and restarts the server, which takes a few seconds. Anyone watching loses the stream and can open the channel again; you stay signed in."
}

/** The services as switches, with the options the checked ones take, and one Save. */
internal fun FlowContent.servicesPanel(chrome: Chrome, view: SetupView) = panel("Services", id = "services", aside = { span("muted") { +"${view.services.count { it.on }} of ${view.services.size} on" } }) {
    p("muted small") {
        +(if (view.hasSources) "Every free service the server can read. Tick the ones you want; each brings its channels and guide." else "No source file yet. Tick the services you want and save; the server then imports their channels and guides.")
    }
    if (view.errors.isNotEmpty()) notice("Not saved:", view.errors)
    form(action = "/admin/sources/services", method = FormMethod.post, classes = "stack") {
        id = "services-form"
        hiddenInput(name = "csrf") { value = chrome.session.csrf }
        ul("services") {
            for (row in view.services) li {
                val info = row.info
                val checked = view.proposed?.let { info.service in it.getAll(serviceField("")).orEmpty() } ?: row.on
                label("switch") {
                    checkBoxInput(name = serviceField(info.service)) { value = info.service; this.checked = checked }
                    span("name") { +info.label }
                    span("muted small") { +info.carries }
                }
                if (info.options.isNotEmpty()) div("options") {
                    for (option in info.options) div("field") {
                        val id = "opt-${info.service}-${option.key}"
                        label { htmlFor = id; +option.label; if (option.required) +" (required)" }
                        textInput(name = optionField(info.service, option.key)) {
                            this.id = id
                            value = view.proposed?.get(optionField(info.service, option.key)) ?: row.options[option.key] ?: ""
                            placeholder = option.hint.substringBefore(';').substringBefore(',')
                        }
                        span("hint") { +option.hint }
                    }
                }
            }
        }
        if (view.canBuildStarter) label("switch") {
            checkBoxInput(name = STARTER_FIELD) { value = "on"; checked = view.proposed?.wantsStarter() ?: true }
            span("name") { +"Build the starter lineup too" }
            span("muted small") { +"One station per channel across these services, grouped and numbered in blocks; channels come on as the server verifies them. Untick to write a lineup policy yourself." }
        }
        restartNote()
        div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Save and restart" } }
    }
}

/** The M3U playlists of the source file, each with Remove, and the form that adds one. */
internal fun FlowContent.playlistsPanel(chrome: Chrome, view: SetupView) = panel("M3U playlists", id = "playlists") {
    if (view.playlists.isEmpty()) p("muted") { +"No M3U playlist. Add one for a provider the server has no built-in client for." }
    else div("scroll") {
        table {
            caption("sr") { +"M3U playlists" }
            thead { tr { col("ID"); col("Playlist"); col("Guide"); col("Action", "sr") } }
            tbody {
                for (playlist in view.playlists) tr {
                    th { attributes["scope"] = "row"; +playlist.id }
                    td("muted") { +(runCatching { java.net.URI(playlist.playlistUrl!!).host }.getOrNull() ?: "a URL") }
                    td("muted") { +(playlist.guideUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: "a URL" } ?: "none") }
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
    details {
        summary { +"Add an M3U playlist" }
        if (view.errors.isNotEmpty() && view.proposed?.contains("url") == true) notice("Not added:", view.errors)
        form(action = "/admin/sources/playlist", method = FormMethod.post, classes = "stack") {
            hiddenInput(name = "csrf") { value = chrome.session.csrf }
            div("runtime") {
                div("field") {
                    label { htmlFor = "pl-id"; +"ID" }
                    textInput(name = "id") { id = "pl-id"; value = view.proposed?.get("id") ?: ""; required = true; pattern = "[a-zA-Z0-9_-]{1,64}" }
                    span("hint") { +"A short name, such as iptv; it names the provider everywhere" }
                }
                div("field") {
                    label { htmlFor = "pl-url"; +"Playlist URL" }
                    urlInput(name = "url") { id = "pl-url"; value = view.proposed?.get("url") ?: ""; required = true }
                    span("hint") { +"The .m3u or .m3u8 address; a token in it stays in the file and never shows here" }
                }
                div("field") {
                    label { htmlFor = "pl-guide"; +"Guide URL" }
                    urlInput(name = "guide") { id = "pl-guide"; value = view.proposed?.get("guide") ?: "" }
                    span("hint") { +"Its XMLTV, plain or gzipped; leave empty for none" }
                }
            }
            restartNote()
            div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Add and restart" } }
        }
    }
}

/** The confirmation of a setup write: what changes, then the restart. */
internal fun FlowContent.setupConfirm(chrome: Chrome, confirm: SetupConfirm, back: String) =
    askDialog(chrome, confirm.title, confirm.action, back, "Save and restart") {
        for ((name, values) in confirm.fields) for (value in values) hiddenInput(name = name) { this.value = value }
        if (confirm.lines.isNotEmpty()) ul { for (line in confirm.lines) li { +line } }
        p { +"The server writes the file and restarts; it is back in a few seconds, and you stay signed in. Anyone watching loses the stream until then." }
    }

/** The home page's setup steps, shown while the server has no source file, or no lineup policy and no lineup. */
internal fun FlowContent.setupCards(chrome: Chrome, setup: SetupService, providers: Int) = panel("Set up", id = "setup") {
    ol("setup") {
        li(if (setup.hasSources) "done" else null) {
            b { +"Add your sources" }
            if (setup.hasSources) span("muted") { +" ${plural(providers, "provider")} configured." }
            else {
                span("muted") { +" Tick the free services you want; no account is needed. Saving builds the lineup too." }
                div("actions") { a("/admin/providers#services", classes = "btn primary") { +"Choose services" } }
            }
        }
        li(if (setup.hasPolicy) "done" else null) {
            b { +"Build the lineup" }
            if (setup.hasPolicy) span("muted") { +" The lineup policy runs after every import." }
            else {
                span("muted") { +" One station per channel across every service, grouped and numbered in blocks; channels come on as the server verifies them." }
                if (setup.hasSources) div("actions") {
                    postForm("/admin/policy/starter", chrome.session) { button(type = ButtonType.submit, classes = "primary") { +"Build the lineup" } }
                } else span("muted small") { +" Add sources first." }
            }
        }
    }
}

/** The page a setup write answers: the server is going down to read what it wrote. The browser returns to the portal on its own. */
internal fun restartingPage(theme: String?, what: String): String = layout("Restarting", null, null, theme, refreshSeconds = 20) {
    div("panel signin") {
        h1("mt-0") { +"Restarting" }
        p { +"$what Hang tight: the server is restarting to read it and is back in a few seconds." }
        p("muted") { +"This page opens the portal again in 20 seconds, still signed in. If the server does not come back, its log says why, and the file it wrote is at the path the Sources page showed." }
        a("/admin", classes = "btn primary") { +"Open the portal" }
    }
}
