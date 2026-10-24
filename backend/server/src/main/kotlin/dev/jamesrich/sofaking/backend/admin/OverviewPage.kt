package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.catalog.hash
import dev.jamesrich.sofaking.backend.setup.SetupService
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.MAIN
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.dd
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.meter
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.sub
import kotlinx.html.summary
import kotlinx.html.title
import kotlinx.html.ul
import java.time.Instant

/**
 * One thing on the overview that needs the operator, where to fix it, and the doc that explains it. [key] names the
 * kind of item, and [fingerprint] its text, so an ignored item comes back when what it says changes.
 */
internal class Attention(
    val tone: Tone,
    val title: String,
    val detail: String?,
    val href: String,
    val action: String,
    val key: String,
    val doc: String? = null,
) {
    val fingerprint: String = hash("$title\n${detail.orEmpty()}").take(16)
}

/** Enabled stations by the state of their guide. */
internal class GuideCoverage(val available: Int, val stale: Int, val empty: Int, val unassigned: Int) {
    val total: Int get() = available + stale + empty + unassigned
    val percent: Int get() = if (total == 0) 0 else (available * 100) / total
}

internal class PlexSummary(
    val lastSyncAt: String?,
    val lastError: String?,
    val dvrDevices: Int,
    val mapped: Int,
    val missing: Int,
    val syncMinutes: Int,
)

internal class OverviewView(
    val attention: List<Attention>,
    /** Items the operator ignored until they change. */
    val ignored: List<Attention> = emptyList(),
    val stations: Int,
    val enabled: Int,
    val guide: GuideCoverage,
    /** Null without a lineup policy. */
    val required: Int?,
    val requiredOff: Int?,
    val suggestions: Int?,
    val findings: Int?,
    val unverified: Int?,
    val providersOk: Int,
    val providersTotal: Int,
    /** Null when Plex sync is not configured. */
    val plex: PlexSummary?,
    val sessions: List<SessionRow>,
    /** Library files and recordings being played. */
    val playing: List<PlayingRow> = emptyList(),
    val lastRun: PolicyApplyResult?,
    val policy: Boolean,
    val stop: StopConfirm? = null,
    /** Setup, for the steps the page shows while the server has no source file or no policy; null when unavailable. */
    val setup: SetupService? = null,
    /** Whether the page asks to confirm building the starter lineup. */
    val confirmStarter: Boolean = false,
) {
    /** [view] with a stop confirmation, or asking to confirm the starter lineup. */
    constructor(view: OverviewView, stop: StopConfirm? = null, confirmStarter: Boolean = false) : this(
        view.attention, view.ignored, view.stations, view.enabled, view.guide, view.required, view.requiredOff,
        view.suggestions, view.findings, view.unverified,
        view.providersOk, view.providersTotal, view.plex, view.sessions, view.playing, view.lastRun, view.policy, stop,
        view.setup, confirmStarter,
    )
}

internal fun overviewPage(chrome: Chrome, view: OverviewView): String = layout("Home", chrome, "/admin") {
    val needs = view.attention.count { it.tone != Tone.OFF }
    pageHead(
        "Home",
        if (needs == 0) {
            "Everything is running. Put your feet up; nothing needs you right now."
        } else {
            "${plural(needs, "thing")} need${if (needs == 1) "s" else ""} you."
        },
    )
    // A server with a lineup made by hand is set up in its own way; the steps stay only
    // while there is nothing to watch.
    view.setup?.takeIf {
        !it.hasSources || (!it.hasPolicy && view.stations == 0)
    }?.let { setupCards(chrome, it, view.providersTotal) }
    attentionPanel(chrome, view)
    div("tiles") {
        channelTiles(view)
        plexTile(view.plex, chrome.now)
        lineupTiles(view)
    }
    div("cols") {
        watchingPanel(chrome, view)
        if (view.policy) lastRunPanel(view.lastRun, chrome.now)
    }
    overviewDialogs(chrome, view)
}

private fun FlowContent.attentionPanel(chrome: Chrome, view: OverviewView) =
    panel("Needs attention", id = "attention") {
        if (view.attention.isEmpty()) {
            p("allgood") {
                +(
                    if (view.ignored.isEmpty()) {
                        "All clear: every provider imported, Plex is in step and every required channel is on."
                    } else {
                        "Nothing new. Ignored items come back when they change."
                    }
                    )
            }
        } else {
            ul("attention") { attentionItems(chrome, view.attention, ignored = false) }
        }
        if (view.ignored.isNotEmpty()) {
            details {
                summary { +"Ignored until it changes (${view.ignored.size})" }
                ul("attention") { attentionItems(chrome, view.ignored, ignored = true) }
            }
        }
    }

private fun UL.attentionItems(chrome: Chrome, items: List<Attention>, ignored: Boolean) {
    for (item in items) {
        li(item.tone.css) {
            div("what") {
                chip(
                    when (item.tone) {
                        Tone.BAD -> "Broken"
                        Tone.WARN -> "Check"
                        Tone.OK -> "Info"
                        Tone.OFF -> "Info"
                    },
                    item.tone,
                )
                b { +item.title }
                if (item.detail != null) span("muted small") { +item.detail }
            }
            div("actions") {
                a(item.href, classes = "fix") { +item.action }
                if (item.doc != null) {
                    a(DOCS_BASE + item.doc, classes = "small") {
                        attributes["rel"] = "noopener"
                        +"Docs"
                    }
                }
                postForm(
                    if (ignored) "/admin/attention/show" else "/admin/attention/ignore",
                    chrome.session,
                    keepScroll = true,
                ) {
                    hiddenInput(name = "key") { value = item.key }
                    hiddenInput(name = "fingerprint") { value = item.fingerprint }
                    button(type = ButtonType.submit, classes = "small quiet") {
                        attributes["aria-label"] =
                            (if (ignored) "Show again: " else "Ignore until it changes: ") + item.title
                        +(if (ignored) "Show again" else "Ignore")
                    }
                }
            }
        }
    }
}

/** The channels that are on, and how many of them have programs ahead. */
private fun DIV.channelTiles(view: OverviewView) {
    a("/admin/lineup?state=enabled", classes = "tile") {
        span("label") { +"Channels on" }
        span("value") {
            +thousands(view.enabled)
            small { +" of ${thousands(view.stations)}" }
        }
        div("meter") {
            span {
                val fill = if (view.stations ==
                    0
                ) {
                    0
                } else {
                    view.enabled * PERCENT / view.stations / METER_STEP * METER_STEP
                }
                attributes["data-fill"] = fill.toString()
            }
        }
    }
    a(
        "/admin/lineup?state=enabled&guide=missing",
        classes = "tile" + if (view.guide.total > 0 && view.guide.percent < GUIDE_WARN_PERCENT) " warn" else "",
    ) {
        span("label") { +"Guide coverage" }
        span("value") { +"${view.guide.percent}%" }
        span("sub") {
            +"${thousands(view.guide.available)} of ${thousands(view.guide.total)} on-air channels have programs ahead"
        }
    }
}

private fun DIV.plexTile(plex: PlexSummary?, now: Instant) {
    a("/admin/plex", classes = "tile" + if (plex?.lastError != null || (plex?.missing ?: 0) > 0) " bad" else "") {
        span("label") { +"Plex sync" }
        when {
            plex == null -> {
                span("value") { small { +"Not set up" } }
                span("sub") {
                    +"Plex sync keeps Plex's channel maps in step; the Players page says how to turn it on."
                }
            }

            plex.lastError != null -> {
                span("value") { +"Failing" }
                span("sub") { +plex.lastError }
            }

            plex.lastSyncAt == null -> {
                span("value") { small { +"Waiting" } }
                span("sub") { +"No sync has run yet; every ${plex.syncMinutes} min." }
            }

            else -> {
                span("value") { ago(plex.lastSyncAt, now) }
                span("sub") {
                    +"${plural(plex.dvrDevices, "tuner")} in Plex, ${thousands(plex.mapped)} channels mapped"
                }
            }
        }
    }
}

/** Required channels and merges, with a policy, and the providers that import. */
private fun DIV.lineupTiles(view: OverviewView) {
    val requiredOff = view.requiredOff ?: 0
    if (view.required != null) {
        a("/admin/required", classes = "tile" + if (requiredOff > 0) " bad" else "") {
            span("label") { +"Required channels" }
            span("value") {
                +thousands(view.required - requiredOff)
                small { +" of ${thousands(view.required)} on" }
            }
            span("sub") {
                +(if (requiredOff == 0) "Every required channel is on." else "${plural(requiredOff, "channel")} off")
            }
        }
    }
    if (view.policy) {
        a("/admin/merges", classes = "tile" + if ((view.suggestions ?: 0) > 0) " warn" else "") {
            span("label") { +"Merges to settle" }
            span("value") { +(view.suggestions?.let(::thousands) ?: "–") }
            span("sub") {
                +(
                    view.findings?.let { "${plural(it, "open finding")} from the last run" }
                        ?: "No policy run yet"
                    )
            }
        }
    }
    a("/admin/providers", classes = "tile" + if (view.providersOk < view.providersTotal) " bad" else "") {
        span("label") { +"Providers" }
        span("value") {
            +thousands(view.providersOk)
            small { +" of ${thousands(view.providersTotal)} importing" }
        }
        span("sub") {
            +(
                if (view.unverified != null) {
                    "${thousands(view.unverified)} channels waiting for a verified source"
                } else {
                    "Channel and guide imports"
                }
                )
        }
    }
}

private fun FlowContent.lastRunPanel(run: PolicyApplyResult?, now: Instant) =
    panel("Last lineup run", aside = { a("/admin/settings#changes") { +"Change log" } }) {
        if (run == null) {
            p("muted") {
                +(
                    "The policy has not built the lineup since the server started; runs follow imports and " +
                        "every 15 minutes."
                    )
            }
        } else {
            dl("kv") {
                dt { +"Ran" }
                dd { ago(run.appliedAt, now) }
                dt { +"Stations" }
                dd { +"${thousands(run.enabledStations)} on of ${thousands(run.stations)}" }
                dt { +"Changes" }
                dd { +(if (run.changed) plural(run.changes, "field") + " changed" else "No change") }
                if (run.devicesNeeded != null) {
                    dt { +"Tuners" }
                    dd { +"${run.devicesNeeded} needed, ${run.devicesConfigured} configured" }
                }
            }
        }
    }

/** The confirmations the home page asks: stopping a session, and building the starter lineup. */
private fun MAIN.overviewDialogs(chrome: Chrome, view: OverviewView) {
    view.stop?.let { stop ->
        val name = stop.row.channel ?: stop.row.active.channelId
        askDialog(
            chrome,
            "Stop the session on $name?",
            "/admin/sessions/${stop.row.active.id}/stop",
            "/admin#watching",
            "Stop",
            danger = true,
        ) {
            p {
                val player = if (stop.row.active.external) "M3U or Plex player" else "Sofa King app"
                val where = stop.row.active.client?.let { " at $it" } ?: ""
                +"The $player$where loses its stream. It can open the channel again."
            }
        }
    }
    if (view.confirmStarter) {
        askDialog(
            chrome,
            "Build the lineup from every configured service?",
            "/admin/policy/starter",
            "/admin#setup",
            "Build and restart",
        ) {
            p {
                +(
                    "Writes a lineup policy: one station per channel, the first service that carries a channel " +
                        "leading it and the others as fallbacks, the channels grouped and numbered in blocks, each " +
                        "coming on once a source of it has played. Then the server restarts and is back in a few " +
                        "seconds; you stay signed in."
                    )
            }
            p("muted small") { +"The policy is a file you can edit later, and the Channels pages change it for you." }
        }
    }
}

/** The guide coverage under which the home page's tile asks for a look. */
private const val GUIDE_WARN_PERCENT = 90
