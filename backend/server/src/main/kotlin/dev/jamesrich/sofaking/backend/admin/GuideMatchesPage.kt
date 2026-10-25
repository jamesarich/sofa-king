package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.api.GuideMatch
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.tr
import kotlin.math.roundToInt

internal fun guideMatchesPage(chrome: Chrome, view: GuideMatchesView?, error: String? = null): String =
    layout("Guide matches", chrome, "/admin/guide-matches") {
        pageHead(
            "Guide matches",
            "Guide channels whose names match a station no guide lists. Accepting writes the channel as the " +
                "station's guide; dismissing never proposes it again. Each choice applies the policy.",
        )
        if (view == null) {
            noPolicy("Matching guides")
            return@layout
        }
        tabs(chrome, "/admin/guide-matches", CHANNEL_TABS)
        notice(error?.let { "Not saved: $it" })
        val stations = view.matches.distinctBy { it.station }.size
        panel(
            "Proposals",
            id = "proposals",
            aside = {
                span("muted") { +"${plural(view.matches.size, "proposal")} for ${plural(stations, "station")}" }
            },
        ) {
            when {
                !view.ran -> p("muted") { +"The policy has not applied yet." }

                view.matches.isEmpty() -> p("muted") { +"No proposals. Every station has a guide, or none matches." }

                else -> {
                    acceptAllForm(chrome, view)
                    matchTable(chrome, view.matches)
                }
            }
        }
        panel("Cleaning names") {
            p("muted") {
                +"Both names lose these before they are compared; set them as guideMatches in the policy. "
                +"Prefixes: ${view.ignorePrefixes.joinToString().ifEmpty { "none" }}. "
                +"Suffixes: ${view.ignoreSuffixes.joinToString().ifEmpty { "none" }}."
            }
        }
    }

private fun FlowContent.acceptAllForm(chrome: Chrome, view: GuideMatchesView) =
    postForm("/admin/guide-matches/accept-all", chrome.session, keepScroll = true) {
        val count = view.matches.groupBy { it.station }.values.count { it.first().confidence >= view.acceptAbove }
        button(type = ButtonType.submit, classes = "primary") {
            disabled = count == 0
            +"Accept all at ${percent(view.acceptAbove)} or more"
        }
        small("muted") { +" ${plural(count, "station")}; a tie between two channels is left for you." }
    }

private fun FlowContent.matchTable(chrome: Chrome, matches: List<GuideMatch>) = div("scroll") {
    table {
        caption("sr") { +"Proposed guide channels" }
        thead {
            tr {
                col("Number")
                col("Station")
                col("Guide channel")
                col("Confidence")
                col("Decide")
            }
        }
        tbody {
            for (match in matches) {
                tr {
                    td { +match.number }
                    td { +match.stationName }
                    td {
                        +match.channelName
                        small("muted") {
                            +" "
                            code { +match.channelId }
                            +" in ${match.guideId}"
                        }
                    }
                    td { +percent(match.confidence) }
                    td {
                        div("actions") {
                            choiceForm(chrome, match, "/admin/guide-matches/accept", "Accept", "small primary")
                            choiceForm(chrome, match, "/admin/guide-matches/dismiss", "Dismiss", "small")
                        }
                    }
                }
            }
        }
    }
}

/** A button that posts one station and guide channel to [action]. */
private fun FlowContent.choiceForm(chrome: Chrome, match: GuideMatch, action: String, label: String, classes: String) =
    postForm(action, chrome.session, keepScroll = true) {
        hiddenInput(name = "station") { value = match.station }
        hiddenInput(name = "guideId") { value = match.guideId }
        hiddenInput(name = "channelId") { value = match.channelId }
        button(type = ButtonType.submit, classes = classes) {
            attributes["aria-label"] = "$label ${match.channelName} for ${match.stationName}"
            +label
        }
    }

private fun percent(value: Double): String = "${(value * PERCENT).roundToInt()}%"
