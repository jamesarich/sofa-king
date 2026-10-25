package dev.jamesrich.sofaking.backend.admin

import kotlinx.html.A
import kotlinx.html.DIV
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.classes
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.emptyMap
import kotlinx.html.footer
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.header
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.s
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.th
import kotlinx.html.time
import kotlinx.html.title
import kotlinx.html.ul
import java.time.Instant

/**
 * What every signed-in page carries besides its content: the session, the render time, a one-shot notice, nav counts,
 * the server revision and start time for the footer, the color scheme the operator chose, and the page to come back
 * to after choosing one.
 */
internal class Chrome(
    val session: AdminSession,
    val now: Instant,
    val flash: Flash? = null,
    val badges: Map<String, Int> = emptyMap(),
    val revision: String? = null,
    val startedAt: Instant? = null,
    val theme: String? = null,
    val back: String = "/admin",
)

/** The schemes the operator can pin; anything else follows the device. */
internal val THEMES = listOf("light", "dark")

/** A notice shown once after a redirect; [target] names the row it concerns. */
internal class Flash(val message: String, val tone: Tone = Tone.OK, val target: String? = null)

internal enum class Tone(val css: String) { OK("ok"), WARN("warn"), BAD("bad"), OFF("off") }

/**
 * The stylesheet's path, public like the icon, and the href pages link, versioned by the sheet's content so the
 * day-long cache never pairs an old sheet with a new page.
 */
internal const val STYLESHEET_PATH = "/admin/static/admin.css"

/**
 * One of the portal's destinations: where it starts, its label, the badge key its count comes
 * from, and the page paths it holds.
 */
internal class Section(
    val href: String,
    val label: String,
    val badge: String? = null,
    val pages: List<String> = listOf(href),
) {
    fun holds(path: String?): Boolean = path != null && path in pages
}

/** One page inside a section, shown as a tab on every page of that section. */
internal class Tab(val href: String, val label: String, val badge: String? = null)

/**
 * The portal's destinations: the home page, the lineup, where channels come from, what plays them, the
 * settings and the server itself.
 */
internal val NAV: List<Section> = listOf(
    Section("/admin", "Home"),
    Section(
        "/admin/lineup",
        "Channels",
        "channels",
        listOf(
            "/admin/lineup",
            "/admin/required",
            "/admin/merges",
            "/admin/guide-matches",
            "/admin/groups",
            "/admin/virtual",
        ),
    ),
    Section("/admin/providers", "Sources", "sources", listOf("/admin/providers", "/admin/ads")),
    Section("/admin/recordings", "Recordings", "recordings", listOf("/admin/recordings", "/admin/recordings/calendar")),
    Section("/admin/viewers", "Players", "players", listOf("/admin/viewers", "/admin/history", "/admin/plex")),
    Section("/admin/library", "Library"),
    Section("/admin/settings", "Settings", pages = listOf("/admin/settings", "/admin/connect")),
    Section(
        "/admin/system/about",
        "System",
        "system",
        listOf(
            "/admin/system/about",
            "/admin/system/activity",
            "/admin/system/changes",
            "/admin/system/jobs",
            "/admin/system/backup",
            "/admin/system/events",
            "/admin/api",
        ),
    ),
)

/** The Channels section's tabs; all but Channels and Virtual exist only under a lineup policy. */
internal val CHANNEL_TABS =
    listOf(
        Tab("/admin/lineup", "Channels"),
        Tab("/admin/required", "Required", "required"),
        Tab("/admin/merges", "Merges", "merges"),
        Tab("/admin/guide-matches", "Guide matches"),
        Tab("/admin/groups", "Groups"),
        Tab("/admin/virtual", "Virtual"),
    )

/** The Channels section's tabs there are: all of them under a policy, else Channels and Virtual. */
internal fun channelTabs(policy: Boolean): List<Tab> =
    if (policy) CHANNEL_TABS else CHANNEL_TABS.filter { it.href == "/admin/lineup" || it.href == "/admin/virtual" }

internal val SOURCE_TABS = listOf(Tab("/admin/providers", "Sources", "sources"), Tab("/admin/ads", "Ads"))
internal val PLAYER_TABS =
    listOf(Tab("/admin/viewers", "Devices"), Tab("/admin/history", "History"), Tab("/admin/plex", "Plex", "plex"))
internal val RECORDING_TABS =
    listOf(Tab("/admin/recordings", "Recordings", "recordings"), Tab("/admin/recordings/calendar", "Calendar"))
internal val SETTINGS_TABS = listOf(Tab("/admin/settings", "Settings"), Tab("/admin/connect", "Connect"))
internal val SYSTEM_TABS =
    listOf(
        Tab("/admin/system/about", "About"),
        Tab("/admin/system/activity", "Activity"),
        Tab("/admin/system/changes", "Changes"),
        Tab("/admin/system/jobs", "Jobs", "jobs"),
        Tab("/admin/system/backup", "Backup"),
        Tab("/admin/system/events", "Events"),
        Tab("/admin/api", "API"),
    )

/** The section's pages as a row of tabs under the heading, the current one marked. */
internal fun FlowContent.tabs(chrome: Chrome, current: String, items: List<Tab>) = nav("tabs") {
    attributes["aria-label"] = "Section"
    for (tab in items) {
        a(tab.href) {
            if (tab.href == current) attributes["aria-current"] = "page"
            +tab.label
            val count = tab.badge?.let { chrome.badges[it] } ?: 0
            if (count > 0) {
                span("count") {
                    attributes["aria-label"] = "$count need attention"
                    +count.toString()
                }
            }
        }
    }
}

/** A POST form with the CSRF token; [keepScroll] keeps the page where it is when htmx swaps the answer in. */
internal fun FlowContent.postForm(
    action: String,
    session: AdminSession,
    keepScroll: Boolean = false,
    classes: String = "inline",
    block: FORM.() -> Unit,
) = form(action = action, method = FormMethod.post, classes = classes) {
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

internal fun FlowContent.panel(
    title: String?,
    id: String? = null,
    aside: (FlowContent.() -> Unit)? = null,
    block: DIV.() -> Unit,
) = section {
    if (id != null) this.id = id
    div("panel") {
        if (title != null) {
            h2 {
                +title
                if (aside != null) span("aside") { aside() }
            }
        }
        block()
    }
}

/** An instant as "12 min ago", with the exact time on hover; [never] when there is none. */
internal fun FlowContent.ago(iso: String?, now: Instant, never: String = "never") {
    val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (instant == null) {
        +(iso ?: never)
        return
    }
    time {
        attributes["datetime"] = iso
        attributes["title"] = iso
        +relative(instant, now)
    }
}
