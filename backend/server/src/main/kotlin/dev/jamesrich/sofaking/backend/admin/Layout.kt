package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.hash
import kotlinx.html.BODY
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.MARK
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.hidden
import kotlinx.html.hiddenInput
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.mark
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.role
import kotlinx.html.s
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.summary
import kotlinx.html.svg
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * The shared page: the brand bar, the grouped nav and sign-out for a signed-in [chrome], htmx boosting its links and
 * forms. [theme] pins the color scheme; null follows the device.
 */
internal fun layout(
    title: String,
    chrome: Chrome?,
    current: String?,
    theme: String? = chrome?.theme,
    refreshSeconds: Int? = null,
    content: MAIN.() -> Unit,
): String = "<!DOCTYPE html>\n" + createHTML(prettyPrint = false).html {
    lang = "en"
    if (theme in THEMES) attributes["data-theme"] = theme!!
    headTags(title, chrome, theme, refreshSeconds)
    body {
        if (chrome != null) {
            attributes["hx-boost"] = "true"
            attributes["hx-headers"] = """{"X-CSRF-Token":"${chrome.session.csrf}"}"""
        }
        a("#content", classes = "skip") { +"Skip to content" }
        topBar(chrome, current)
        main {
            id = "content"
            content()
        }
        siteFooter(chrome)
        chrome?.ask?.let { askDialog(chrome, it) }
        val flash = chrome?.flash
        if (flash != null) {
            div("toast ${flash.tone.css}") {
                attributes["role"] = "status"
                +flash.message
            }
        }
    }
}

private fun HTML.headTags(title: String, chrome: Chrome?, theme: String?, refreshSeconds: Int?) = head {
    meta(charset = "utf-8")
    meta(name = "viewport", content = "width=device-width, initial-scale=1")
    when (theme) {
        "light" -> meta(name = "theme-color", content = LIGHT_THEME_COLOR)

        "dark" -> meta(name = "theme-color", content = DARK_THEME_COLOR)

        else -> {
            meta(name = "theme-color", content = LIGHT_THEME_COLOR) {
                attributes["media"] = "(prefers-color-scheme: light)"
            }
            meta(name = "theme-color", content = DARK_THEME_COLOR)
        }
    }
    meta(name = "htmx-config", content = HTMX_CONFIG)
    if (refreshSeconds != null) {
        meta {
            attributes["http-equiv"] = "refresh"
            attributes["content"] = "$refreshSeconds; url=/admin"
        }
    }
    title { +"$title · Sofa King admin" }
    link(rel = "icon", href = "/admin/static/icon.svg", type = "image/svg+xml")
    link(rel = "stylesheet", href = STYLESHEET_HREF)
    if (chrome != null) script(src = "/admin/static/htmx.min.js") {}
}

private fun BODY.topBar(chrome: Chrome?, current: String?) = header("top") {
    div("top-in") {
        a("/admin", classes = "brand") {
            unsafe { +MARK }
            span {
                +"Sofa King"
                +" "
                small { +"admin" }
            }
        }
        if (chrome != null) {
            portalMenu(chrome, current)
            postForm("/admin/logout", chrome.session, classes = "inline signout") {
                button(type = ButtonType.submit, classes = "quiet") { +"Sign out" }
            }
        }
    }
}

/** The portal's sections; on a phone they fold into this disclosure, on a wide screen it shows open with no summary. */
private fun FlowContent.portalMenu(chrome: Chrome, current: String?) = details("menu") {
    val here = NAV.firstOrNull { it.holds(current) }
    summary {
        +"Menu"
        if (here != null) span("muted") { +" · ${here.label}" }
        val total = NAV.sumOf { section -> section.badge?.let { chrome.badges[it] } ?: 0 }
        attentionCount(total)
    }
    nav("main") {
        attributes["aria-label"] = "Portal"
        for (section in NAV) {
            a(section.href) {
                if (section === here) {
                    attributes["aria-current"] = if (section.href == current) "page" else "true"
                }
                +section.label
                attentionCount(section.badge?.let { chrome.badges[it] } ?: 0)
            }
        }
    }
}

/** How many items need attention, as a badge; nothing when none do. */
private fun FlowContent.attentionCount(count: Int) {
    if (count > 0) {
        span("count") {
            attributes["aria-label"] = "$count need attention"
            +count.toString()
        }
    }
}

private fun BODY.siteFooter(chrome: Chrome?) = footer {
    span {
        +"Sofa King well organized."
        if (chrome?.revision != null) {
            +" Revision "
            code { +chrome.revision }
            +"."
        }
        if (chrome?.startedAt != null) +" Up ${uptime(chrome.startedAt, chrome.now)}."
    }
    if (chrome != null) themeForm(chrome)
}

private fun FlowContent.themeForm(chrome: Chrome) =
    form(action = "/admin/theme", method = FormMethod.post, classes = "inline theme") {
        attributes["aria-label"] = "Color scheme"
        hiddenInput(name = "csrf") { value = chrome.session.csrf }
        hiddenInput(name = "back") { value = chrome.back }
        span("muted") { +"Theme" }
        for ((value, label) in listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark")) {
            button(type = ButtonType.submit, classes = "small quiet") {
                name = "theme"
                this.value = value
                attributes["aria-pressed"] = ((chrome.theme ?: "auto") == value).toString()
                +label
            }
        }
    }

private val STYLESHEET_HREF: String =
    STYLESHEET_PATH + "?v=" + Chrome::class.java.classLoader.getResourceAsStream("admin/admin.css")!!.use { sheet ->
        hash(sheet.readAllBytes()).take(16)
    }

/** The crown-on-a-sofa mark of the TV app's banner, drawn inline so it needs no image request. */
private const val MARK = """<svg viewBox="0 0 120 120" aria-hidden="true" focusable="false"><path fill="#B3A1EA" """ +
    """d="M22,46 Q22,40 28,40 L92,40 Q98,40 98,46 L98,70 L22,70 Z"/><path fill="#C9B8FF" d="M10,62 Q10,58 14,58 """ +
    """L106,58 Q110,58 110,62 L110,92 L10,92 Z"/><path fill="#C9B8FF" d="M6,58 Q6,52 12,52 L20,52 L20,96 L6,96 """ +
    """Z"/><path fill="#C9B8FF" d="M100,52 L108,52 Q114,52 114,58 L114,96 L100,96 Z"/><path fill="#9C8AD4" """ +
    """d="M20,72 L100,72 L100,75 L20,75 Z M14,96 L14,104 L20,104 L20,96 Z M100,96 L100,104 L106,104 """ +
    """L106,96 Z"/><path fill="#FFB547" d="M36,46 L36,30 L48,38 L60,20 L72,38 L84,30 L84,46 """ +
    """Z"/><path fill="#E0902A" d="M36,40 L84,40 L84,46 L36,46 Z"/><path fill="#14111F" """ +
    """d="M60,42 L62,44 L60,46 L58,44 Z"/></svg>"""

/** The browser chrome's color beside each scheme's page background. */
private const val DARK_THEME_COLOR = "#14111F"

private const val LIGHT_THEME_COLOR = "#F7F4FC"

private const val HTMX_CONFIG =
    """{"allowEval":false,"allowScriptTags":false,"includeIndicatorStyles":false,"historyCacheSize":0,""" +
        """"refreshOnHistoryMiss":true,""" +
        """"responseHandling":[{"code":"204","swap":false},{"code":"[23]..","swap":true},{"code":"4..","swap":""" +
        """true,"error":false},{"code":"...","swap":false,"error":true}]}"""
