package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.GuideStatus
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.api.ProviderStatus
import kotlinx.html.A
import kotlinx.html.FlowContent
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.emptyMap
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.section
import kotlinx.html.small
import kotlinx.html.source
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import java.time.Instant

/**
 * A configured provider: its import status, what kind it is, and the policy entries that take its channels, by index.
 */
internal class ProviderRow(
    val status: ProviderStatus,
    val kind: String,
    val entries: List<IndexedValue<PolicyProvider>>,
)

internal class ProvidersView(
    val rows: List<ProviderRow>,
    val guides: List<GuideStatus>,
    /** The policy's provider entries in priority order; null without a policy. */
    val entries: List<PolicyProvider>?,
    val revision: String?,
    val stationsLed: Map<String, Int> = emptyMap(),
    /** The source file's providers and guides, described without their secrets, for the Source file section. */
    val providerSummaries: List<SourceSummary> = emptyList(),
    val guideSummaries: List<SourceSummary> = emptyList(),
    /** The services and playlists as the portal edits them; null when setup is unavailable. */
    val setup: SetupView? = null,
) {
    constructor(
        view: ProvidersView,
        setup: SetupView?,
    ) : this(
        view.rows,
        view.guides,
        view.entries,
        view.revision,
        view.stationsLed,
        view.providerSummaries,
        view.guideSummaries,
        setup,
    )
}

internal fun providersPage(chrome: Chrome, view: ProvidersView, extra: (MAIN.() -> Unit)? = null): String =
    layout("Sources", chrome, "/admin/providers") {
        pageHead(
            "Sources",
            "Where channels and guides come from, and whether their last import worked." +
                if (view.entries != null) {
                    " The policy takes channels from its provider entries in priority order."
                } else {
                    ""
                },
        )
        tabs(chrome, "/admin/providers", SOURCE_TABS)
        extra?.invoke(this)
        view.setup?.let { setup ->
            servicesPanel(chrome, setup)
            playlistsPanel(chrome, setup)
            setup.confirm?.let { setupConfirm(chrome, it, "/admin/providers#services") }
        }
        importsPanel(view, chrome.now)
        sourceFilePanel(view.providerSummaries, view.guideSummaries)
        guidesPanel(view.guides, chrome.now)
    }

private fun FlowContent.importsPanel(view: ProvidersView, now: Instant) = panel(
    "Channel providers",
    id = "imports",
    aside = {
        span("muted") {
            +(
                "${view.rows.count { it.status.errorCode == null && it.status.lastSuccessAt != null }} of " +
                    "${view.rows.size} importing"
                )
        }
    },
) {
    div("scroll") {
        table {
            caption("sr") { +"Channel providers" }
            thead {
                tr {
                    col("Provider")
                    col("Kind")
                    col("Channels", "num")
                    col("Last import")
                    col("Status")
                    if (view.entries != null) col("Leads", "num")
                }
            }
            tbody {
                for (row in view.rows.sortedWith(compareBy({ it.status.errorCode == null }, { it.status.id }))) {
                    tr {
                        val status = row.status
                        th {
                            attributes["scope"] = "row"
                            +status.id
                        }
                        td("muted") { +row.kind }
                        td("num") { +thousands(status.channels) }
                        td("nowrap") { ago(status.lastSuccessAt, now) }
                        td {
                            importChip(status.errorCode, status.error, status.lastSuccessAt)
                            if (status.skippedEntries > 0) {
                                span("muted small") { +" ${thousands(status.skippedEntries)} skipped" }
                            }
                        }
                        if (view.entries != null) td("num") { +thousands(view.stationsLed[status.id] ?: 0) }
                    }
                }
            }
        }
    }
}

/** The import's state, with why the last one failed beside a failure. */
private fun FlowContent.importChip(errorCode: String?, error: String?, lastSuccessAt: String?) {
    when {
        errorCode != null -> chip(errorCode, Tone.BAD)
        lastSuccessAt == null -> chip("Waiting", Tone.WARN)
        else -> chip("OK", Tone.OK)
    }
    if (errorCode != null && error != null) p("why small muted") { +error }
}

private fun FlowContent.guidesPanel(guides: List<GuideStatus>, now: Instant) = panel("Guides", id = "guides") {
    if (guides.isEmpty()) {
        p("muted") { +"No guide sources are configured." }
    } else {
        div("scroll") {
            table {
                caption("sr") { +"Guides" }
                thead {
                    tr {
                        col("Guide")
                        col("Channels", "num")
                        col("Programs", "num")
                        col("Last import")
                        col("Status")
                    }
                }
                tbody {
                    for (guide in guides.sortedWith(compareBy({ it.errorCode == null }, { it.id }))) {
                        tr {
                            th {
                                attributes["scope"] = "row"
                                +guide.id
                            }
                            td("num") { +thousands(guide.channels) }
                            td("num") { +thousands(guide.programs) }
                            td("nowrap") { ago(guide.lastSuccessAt, now) }
                            td { importChip(guide.errorCode, guide.error, guide.lastSuccessAt) }
                        }
                    }
                }
            }
        }
    }
}
