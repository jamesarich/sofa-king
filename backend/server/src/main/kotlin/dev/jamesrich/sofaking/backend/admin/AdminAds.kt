package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.ads.LibraryStats
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.backend.store.AdChannelRecord
import kotlinx.html.A
import kotlinx.html.a
import kotlinx.html.caption
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.s
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.time
import kotlinx.html.tr

/** One channel's ad census, named as the lineup names it. */
internal class AdChannelRow(val record: AdChannelRecord, val name: String?, val listening: Boolean)

internal class AdsView(
    val mode: LearnedBreaks,
    val listeners: Int,
    val library: LibraryStats?,
    val listening: Int,
    val channels: List<AdChannelRow>,
)

/** A length of time as "3 h 12 min", "7 min" or "40 s". */
internal fun spoken(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min"
        s >= 60 -> "${s / 60} min"
        else -> "$s s"
    }
}

internal fun adsPage(chrome: Chrome, view: AdsView): String = layout("Ads", chrome, "/admin/ads") {
    val now = chrome.now
    pageHead(
        "Ads",
        "What the server knows about each channel's ad breaks: the breaks its provider marks, the ads it has learned to recognize, and the channels it listens to in the background to learn more.",
    )
    tabs(chrome, "/admin/ads", SOURCE_TABS)
    panel("Learning", id = "learning") {
        dl("kv") {
            dt { +"Learned breaks" }
            dd {
                when (view.mode) {
                    LearnedBreaks.OFF -> chip("Off", Tone.OFF)
                    LearnedBreaks.SHADOW -> chip("Shadow: logged, never blanked", Tone.WARN)
                    LearnedBreaks.ON -> chip("On", Tone.OK)
                }
            }
            dt { +"Background listeners" }
            dd { +"${thousands(view.listening)} of ${thousands(view.listeners)} listening" }
            val library = view.library
            if (library != null) {
                dt { +"Ads learned" }
                dd {
                    +"${plural(
                        library.entries,
                        "ad",
                    )}, ${thousands(library.blanking)} trusted to blank, ${thousands(library.blocked)} blocked"
                }
                dt { +"Canary precision (24 h)" }
                dd {
                    val precision = library.precision
                    if (precision == null) {
                        span("muted") { +"no checks yet" }
                    } else {
                        +"%.1f%% of %s".format(
                            java.util.Locale.US,
                            precision * 100,
                            plural(library.canaryHits + library.canaryFalse, "check"),
                        )
                    }
                    if (library.frozen) {
                        +" "
                        chip("Frozen: matching paused", Tone.BAD)
                    }
                }
            }
        }
        p("muted small") {
            +(
                "Exact markers always win. A learned break only blanks with SOFA_LEARNED_BREAKS on; in shadow the " +
                    "server logs what it would have blanked. Change both in "
                )
            a("/admin/settings") { +"Settings" }
            +"."
        }
    }
    panel("Channels", id = "channels") {
        if (view.channels.isEmpty()) {
            p("muted mt-0") {
                +"No channel has played yet. Each one appears here after a viewer or a listener has played it."
            }
            return@panel
        }
        div("scroll") {
            table {
                caption("sr") { +"Ad census by channel" }
                thead {
                    tr {
                        col("Channel")
                        col("Provider")
                        col("Watched", "num")
                        col("Listened", "num")
                        col("Marked breaks", "num")
                        col("Learned breaks", "num")
                        col("Last marked")
                        col("Last watched")
                    }
                }
                tbody {
                    for (row in view.channels) {
                        tr {
                            val record = row.record
                            th {
                                attributes["scope"] = "row"
                                +(row.name ?: record.channelId)
                                if (row.listening) {
                                    +" "
                                    span("chip ${Tone.OK.css}") { +"listening" }
                                }
                            }
                            td { +record.providerId }
                            td("num nowrap") { +spoken(record.watchedMs) }
                            td("num nowrap") { +spoken(record.listenedMs) }
                            td("num") { +thousands(record.breaks) }
                            td("num") { +thousands(record.learned) }
                            td("nowrap") { ago(record.markedAt?.toString(), now) }
                            td("nowrap") { ago(record.lastWatchedAt?.toString(), now) }
                        }
                    }
                }
            }
        }
    }
}
