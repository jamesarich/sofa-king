package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.catalog.hash
import java.time.Duration
import java.time.Instant
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.MAIN
import kotlinx.html.MARK
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.emptyMap
import kotlinx.html.footer
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.hidden
import kotlinx.html.hiddenInput
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.mark
import kotlinx.html.math
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.s
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.svg
import kotlinx.html.th
import kotlinx.html.time
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.html.stream.createHTML

/**
 * What every signed-in page carries besides its content: the session, the render time, a one-shot notice, nav counts,
 * the server revision and start time for the footer, the colour scheme the operator chose, and the page to come back
 * to after choosing one.
 */
internal class Chrome(
    val session: AdminSession, val now: Instant, val flash: Flash? = null, val badges: Map<String, Int> = emptyMap(),
    val revision: String? = null, val startedAt: Instant? = null, val theme: String? = null, val back: String = "/admin",
)

/** The schemes the operator can pin; anything else follows the device. */
internal val THEMES = listOf("light", "dark")

/** A running time as "3 d 4 h", "5 h 12 min" or "7 min". */
internal fun uptime(from: Instant, now: Instant): String {
    val s = Duration.between(from, now).seconds.coerceAtLeast(0)
    return when {
        s >= 86_400 -> "${s / 86_400} d ${s % 86_400 / 3600} h"
        s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min"
        else -> "${s / 60} min"
    }
}

/** A notice shown once after a redirect; [target] names the row it concerns. */
internal class Flash(val message: String, val tone: Tone = Tone.OK, val target: String? = null)

internal enum class Tone(val css: String) { OK("ok"), WARN("warn"), BAD("bad"), OFF("off") }

/** The stylesheet's path, public like the icon, and the href pages link, versioned by the sheet's content so the day-long cache never pairs an old sheet with a new page. */
internal const val STYLESHEET_PATH = "/admin/static/admin.css"
private val STYLESHEET_HREF: String = STYLESHEET_PATH + "?v=" + Chrome::class.java.classLoader.getResourceAsStream("admin/admin.css")!!.use { sheet ->
    hash(sheet.readAllBytes()).take(16)
}

/** The crown-on-a-sofa mark of the TV app's banner, drawn inline so it needs no image request. */
private const val MARK = """<svg viewBox="0 0 120 120" aria-hidden="true" focusable="false"><path fill="#B3A1EA" d="M22,46 Q22,40 28,40 L92,40 Q98,40 98,46 L98,70 L22,70 Z"/><path fill="#C9B8FF" d="M10,62 Q10,58 14,58 L106,58 Q110,58 110,62 L110,92 L10,92 Z"/><path fill="#C9B8FF" d="M6,58 Q6,52 12,52 L20,52 L20,96 L6,96 Z"/><path fill="#C9B8FF" d="M100,52 L108,52 Q114,52 114,58 L114,96 L100,96 Z"/><path fill="#9C8AD4" d="M20,72 L100,72 L100,75 L20,75 Z M14,96 L14,104 L20,104 L20,96 Z M100,96 L100,104 L106,104 L106,96 Z"/><path fill="#FFB547" d="M36,46 L36,30 L48,38 L60,20 L72,38 L84,30 L84,46 Z"/><path fill="#E0902A" d="M36,40 L84,40 L84,46 L36,46 Z"/><path fill="#14111F" d="M60,42 L62,44 L60,46 L58,44 Z"/></svg>"""

/** The browser chrome's colour beside each scheme's page background. */
private const val DARK_THEME_COLOR = "#14111F"
private const val LIGHT_THEME_COLOR = "#F7F4FC"

private const val HTMX_CONFIG =
    """{"allowEval":false,"allowScriptTags":false,"includeIndicatorStyles":false,"historyCacheSize":0,"refreshOnHistoryMiss":true,""" +
        """"responseHandling":[{"code":"204","swap":false},{"code":"[23]..","swap":true},{"code":"4..","swap":true,"error":false},{"code":"...","swap":false,"error":true}]}"""

/** One of the portal's destinations: where it starts, its label, the badge key its count comes from, and the page paths it holds. */
internal class Section(val href: String, val label: String, val badge: String? = null, val pages: List<String> = listOf(href)) {
    fun holds(path: String?): Boolean = path != null && path in pages
}

/** One page inside a section, shown as a tab on every page of that section. */
internal class Tab(val href: String, val label: String, val badge: String? = null)

/** The portal's destinations: the home page, the lineup, where channels come from, what plays them, the settings and the server itself. */
internal val NAV: List<Section> = listOf(
    Section("/admin", "Home"),
    Section("/admin/lineup", "Channels", "channels", listOf("/admin/lineup", "/admin/required", "/admin/merges", "/admin/groups")),
    Section("/admin/providers", "Sources", "sources", listOf("/admin/providers", "/admin/ads")),
    Section("/admin/recordings", "Recordings", "recordings"),
    Section("/admin/viewers", "Players", "players", listOf("/admin/viewers", "/admin/plex")),
    Section("/admin/library", "Library"),
    Section("/admin/settings", "Settings"),
    Section("/admin/system/about", "System", "system", listOf("/admin/system/about", "/admin/system/jobs", "/admin/system/events", "/admin/api")),
)

/** The Channels section's tabs; Required, Merges and Groups exist only under a lineup policy. */
internal val CHANNEL_TABS = listOf(Tab("/admin/lineup", "Channels"), Tab("/admin/required", "Required", "required"), Tab("/admin/merges", "Merges", "merges"), Tab("/admin/groups", "Groups"))
internal val SOURCE_TABS = listOf(Tab("/admin/providers", "Sources", "sources"), Tab("/admin/ads", "Ads"))
internal val PLAYER_TABS = listOf(Tab("/admin/viewers", "Devices"), Tab("/admin/plex", "Plex", "plex"))
internal val SYSTEM_TABS = listOf(Tab("/admin/system/about", "About"), Tab("/admin/system/jobs", "Jobs", "jobs"), Tab("/admin/system/events", "Events"), Tab("/admin/api", "API"))

/** The section's pages as a row of tabs under the heading, the current one marked. */
internal fun FlowContent.tabs(chrome: Chrome, current: String, items: List<Tab>) = nav("tabs") {
    attributes["aria-label"] = "Section"
    for (tab in items) a(tab.href) {
        if (tab.href == current) attributes["aria-current"] = "page"
        +tab.label
        val count = tab.badge?.let { chrome.badges[it] } ?: 0
        if (count > 0) span("count") { attributes["aria-label"] = "$count need attention"; +count.toString() }
    }
}

/**
 * The shared page: the brand bar, the grouped nav and sign-out for a signed-in [chrome], htmx boosting its links and
 * forms. [theme] pins the colour scheme; null follows the device.
 */
internal fun layout(title: String, chrome: Chrome?, current: String?, theme: String? = chrome?.theme, refreshSeconds: Int? = null, content: MAIN.() -> Unit): String = "<!DOCTYPE html>\n" + createHTML(prettyPrint = false).html {
    lang = "en"
    if (theme in THEMES) attributes["data-theme"] = theme!!
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        when (theme) {
            "light" -> meta(name = "theme-color", content = LIGHT_THEME_COLOR)
            "dark" -> meta(name = "theme-color", content = DARK_THEME_COLOR)
            else -> {
                meta(name = "theme-color", content = LIGHT_THEME_COLOR) { attributes["media"] = "(prefers-color-scheme: light)" }
                meta(name = "theme-color", content = DARK_THEME_COLOR)
            }
        }
        meta(name = "htmx-config", content = HTMX_CONFIG)
        if (refreshSeconds != null) meta { attributes["http-equiv"] = "refresh"; attributes["content"] = "$refreshSeconds; url=/admin" }
        title { +"$title · Sofa King admin" }
        link(rel = "icon", href = "/admin/static/icon.svg", type = "image/svg+xml")
        link(rel = "stylesheet", href = STYLESHEET_HREF)
        if (chrome != null) script(src = "/admin/static/htmx.min.js") {}
    }
    body {
        if (chrome != null) {
            attributes["hx-boost"] = "true"
            attributes["hx-headers"] = """{"X-CSRF-Token":"${chrome.session.csrf}"}"""
        }
        a("#content", classes = "skip") { +"Skip to content" }
        header("top") {
            div("top-in") {
                a(if (chrome != null) "/admin" else "/admin", classes = "brand") {
                    unsafe { +MARK }
                    span { +"Sofa King"; +" "; small { +"admin" } }
                }
                if (chrome != null) {
                    // On a phone the nav folds into this disclosure; on a wide screen the stylesheet shows it open with no summary.
                    details("menu") {
                        val here = NAV.firstOrNull { it.holds(current) }
                        summary {
                            +"Menu"
                            if (here != null) span("muted") { +" · ${here.label}" }
                            val total = NAV.sumOf { section -> section.badge?.let { chrome.badges[it] } ?: 0 }
                            if (total > 0) span("count") { attributes["aria-label"] = "$total need attention"; +total.toString() }
                        }
                        nav("main") {
                            attributes["aria-label"] = "Portal"
                            for (section in NAV) a(section.href) {
                                if (section === here) attributes["aria-current"] = if (section.href == current) "page" else "true"
                                +section.label
                                val count = section.badge?.let { chrome.badges[it] } ?: 0
                                if (count > 0) span("count") { attributes["aria-label"] = "$count need attention"; +count.toString() }
                            }
                        }
                    }
                    postForm("/admin/logout", chrome.session, classes = "inline signout") { button(type = ButtonType.submit, classes = "quiet") { +"Sign out" } }
                }
            }
        }
        main {
            id = "content"
            content()
        }
        footer {
            span {
                +"Sofa King well organized."
                if (chrome?.revision != null) { +" Revision "; code { +chrome.revision }; +"." }
                if (chrome?.startedAt != null) +" Up ${uptime(chrome.startedAt, chrome.now)}."
            }
            if (chrome != null) form(action = "/admin/theme", method = FormMethod.post, classes = "inline theme") {
                attributes["aria-label"] = "Color scheme"
                hiddenInput(name = "csrf") { value = chrome.session.csrf }
                hiddenInput(name = "back") { value = chrome.back }
                span("muted") { +"Theme" }
                for ((value, label) in listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark")) button(type = ButtonType.submit, classes = "small quiet") {
                    name = "theme"; this.value = value
                    attributes["aria-pressed"] = ((chrome.theme ?: "auto") == value).toString()
                    +label
                }
            }
        }
        val flash = chrome?.flash
        if (flash != null) div("toast ${flash.tone.css}") { attributes["role"] = "status"; +flash.message }
    }
}

/** A POST form with the CSRF token; [keepScroll] keeps the page where it is when htmx swaps the answer in. */
internal fun FlowContent.postForm(action: String, session: AdminSession, keepScroll: Boolean = false, classes: String = "inline", block: FORM.() -> Unit) =
    form(action = action, method = FormMethod.post, classes = classes) {
        // The stream writes a tag's attributes with its first child, so they are set before any.
        if (keepScroll) attributes["hx-swap"] = "innerHTML show:none"
        hiddenInput(name = "csrf") { value = session.csrf }
        block()
    }

internal fun FlowContent.pageHead(title: String, lede: String? = null) {
    h1 { +title }
    if (lede != null) p("lede") { +lede }
}

internal fun FlowContent.notice(message: String?, details: List<String> = emptyList(), tone: Tone = Tone.BAD) {
    if (message == null) return
    div(if (tone == Tone.WARN) "notice warn" else "notice") {
        attributes["role"] = "alert"
        +message
        if (details.isNotEmpty()) ul { for (line in details) li { +line } }
    }
}

internal fun FlowContent.chip(text: String, tone: Tone) = span("chip ${tone.css}") { +text }

/** A column header with `scope="col"`, so a cell reads with its column. */
internal fun TR.col(text: String, classes: String? = null) = th(classes = classes) {
    attributes["scope"] = "col"
    +text
}

internal fun FlowContent.panel(title: String?, id: String? = null, aside: (FlowContent.() -> Unit)? = null, block: DIV.() -> Unit) = section {
    if (id != null) this.id = id
    div("panel") {
        if (title != null) h2 {
            +title
            if (aside != null) span("aside") { aside() }
        }
        block()
    }
}

/** An instant as "12 min ago", with the exact time on hover; [never] when there is none. */
internal fun FlowContent.ago(iso: String?, now: Instant, never: String = "never") {
    val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (instant == null) { +(iso ?: never); return }
    time {
        attributes["datetime"] = iso
        attributes["title"] = iso
        +relative(instant, now)
    }
}

internal fun relative(instant: Instant, now: Instant): String {
    val seconds = Duration.between(instant, now).seconds
    val future = seconds < 0
    val s = kotlin.math.abs(seconds)
    val text = when {
        s < 60 -> return if (future) "in under a minute" else "just now"
        s < 3600 -> "${s / 60} min"
        s < 86_400 -> "${s / 3600} h"
        else -> "${s / 86_400} d"
    }
    return if (future) "in $text" else "$text ago"
}

internal fun thousands(n: Int): String = "%,d".format(java.util.Locale.US, n)

internal fun plural(n: Int, one: String, many: String = one + "s"): String = "${thousands(n)} ${if (n == 1) one else many}"
