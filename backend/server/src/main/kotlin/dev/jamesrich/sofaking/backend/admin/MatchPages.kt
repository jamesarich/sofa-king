package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.library.ItemKind
import dev.jamesrich.sofaking.backend.library.LibraryItemRecord
import dev.jamesrich.sofaking.backend.library.LibraryMatches
import dev.jamesrich.sofaking.backend.library.MatchRow
import dev.jamesrich.sofaking.backend.library.TmdbCandidate
import dev.jamesrich.sofaking.backend.library.TmdbFailure
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.p
import kotlinx.html.searchInput
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** The Library section's tabs. */
internal val LIBRARY_TABS = listOf(Tab("/admin/library", "Libraries"), Tab("/admin/library/unmatched", "Unmatched"))

/** One movie or show and what TMDB finds for [query], or why it could not search. */
internal class MatchSearch(
    val item: LibraryItemRecord,
    val query: String,
    val year: Int?,
    val results: List<TmdbCandidate>,
    val error: String? = null,
)

/** The Unmatched tab: matching the movies and shows TMDB's search missed, by hand. */
internal class MatchPages(private val portal: Portal) {
    fun install(route: Route) = with(portal) {
        route.get("/library/unmatched") {
            val matches = runtime.libraries?.matches
            call.html(unmatchedPage(chrome(), matches?.rows(), runtime.libraries != null))
        }
        route.get("/library/items/{id}/match") {
            val matches = runtime.libraries?.matches ?: return@get seeOther(call, "/admin/library/unmatched")
            val item = matches.item(call.parameters["id"]!!) ?: return@get seeOther(call, "/admin/library/unmatched")
            val query = call.request.queryParameters["q"]?.trim()?.ifEmpty { null } ?: item.title
            val year = call.request.queryParameters["year"]?.let { it.trim().toIntOrNull() } ?: item.year.takeIf {
                call.request.queryParameters["year"] == null
            }
            call.html(matchPage(chrome(), search(matches, item, query, year)))
        }
        route.action("/library/items/{id}/match") { session, form ->
            val matches = runtime.libraries?.matches ?: return@action seeOther(call, "/admin/library/unmatched")
            val item = matches.item(call.parameters["id"]!!)
            val tmdbId = form["tmdb"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val flash = when {
                item == null -> Flash("That item is gone.", Tone.WARN)

                tmdbId == null -> Flash("Give a TMDB id: the number in the entry's address.", Tone.BAD, item.id)

                else -> try {
                    val title = matches.choose(item, tmdbId)
                    Flash(
                        "Matched ${item.title} to $title. Scanning its library to fetch the details.",
                        target = item.id,
                    )
                } catch (e: TmdbFailure) {
                    Flash("Not matched: ${failureWords(e, item, tmdbId)}", Tone.BAD, item.id)
                }
            }
            session.flash.set(flash)
            seeOther(call, "/admin/library/unmatched")
        }
        route.action("/library/items/{id}/automatic") { session, _ ->
            val matches = runtime.libraries?.matches ?: return@action seeOther(call, "/admin/library/unmatched")
            val item = matches.item(call.parameters["id"]!!)
            val flash = if (item != null && matches.clear(item)) {
                Flash("${item.title} is matched by the scan again; its library is scanning.", target = item.id)
            } else {
                Flash("That item had no match chosen by hand.", Tone.WARN)
            }
            session.flash.set(flash)
            seeOther(call, "/admin/library/unmatched")
        }
    }

    private suspend fun search(matches: LibraryMatches, item: LibraryItemRecord, query: String, year: Int?) = try {
        MatchSearch(item, query, year, matches.search(item, query, year))
    } catch (e: TmdbFailure) {
        MatchSearch(item, query, year, emptyList(), "TMDB did not answer the search (${e.code}). Try again shortly.")
    }
}

private fun failureWords(e: TmdbFailure, item: LibraryItemRecord, tmdbId: Int): String = when (e.code) {
    "TMDB_HTTP_404" -> "TMDB has no ${kindWord(item)} $tmdbId."
    else -> "TMDB did not answer (${e.code}). Try again shortly."
}

private fun kindWord(item: LibraryItemRecord) = if (item.kind == ItemKind.SHOW) "show" else "movie"

/** The entry's page on TMDB, for checking a candidate before choosing it. */
private fun tmdbPage(kind: String, id: Int) =
    "https://www.themoviedb.org/${if (kind == ItemKind.SHOW) "tv" else "movie"}/$id"

internal fun unmatchedPage(chrome: Chrome, rows: List<MatchRow>?, libraries: Boolean): String =
    layout("Unmatched", chrome, "/admin/library/unmatched") {
        pageHead(
            "Unmatched",
            "The movies and shows TMDB's search found nothing for, so they have no details or artwork. Find each " +
                "one on TMDB and choose its entry; every scan then looks that entry up in place of a search.",
        )
        tabs(chrome, "/admin/library/unmatched", LIBRARY_TABS)
        when {
            !libraries -> panel(null) { p("muted mt-0") { +"The libraries are off on this server." } }

            rows == null -> panel(null) {
                p("muted mt-0") {
                    +"TMDB lookups are off. Add a TMDB token on the "
                    a("/admin/library#tmdb") { +"Libraries" }
                    +" tab first."
                }
            }

            else -> {
                unmatchedPanel(chrome, rows.filter { it.chosen == null })
                chosenPanel(chrome, rows.filter { it.chosen != null })
            }
        }
    }

private fun FlowContent.unmatchedPanel(chrome: Chrome, rows: List<MatchRow>) = panel("Not found", id = "unmatched") {
    if (rows.isEmpty()) {
        p("muted mt-0") { +"TMDB matched every movie and show it has looked up." }
        return@panel
    }
    matchTable(chrome, rows, "Movies and shows TMDB did not find", "Folder") { row ->
        code { +row.item.path.orEmpty() }
    }
}

private fun FlowContent.chosenPanel(chrome: Chrome, rows: List<MatchRow>) = panel("Matched by hand", id = "chosen") {
    if (rows.isEmpty()) {
        p("muted mt-0") { +"No match chosen by hand yet." }
        return@panel
    }
    matchTable(chrome, rows, "Movies and shows matched by hand", "TMDB entry") { row ->
        val id = row.chosen!!
        a(tmdbPage(row.item.kind, id)) {
            attributes["rel"] = "noopener noreferrer"
            +(row.found?.facts?.title?.takeIf { row.fetched } ?: "TMDB $id")
        }
        if (!row.fetched) span("muted small") { +" · fetched on the next scan" }
        postForm("/admin/library/items/${row.item.id}/automatic", chrome.session) {
            button(type = ButtonType.submit, classes = "small quiet") {
                attributes["aria-label"] = "Let the scan match ${row.item.title}"
                +"Let the scan match it"
            }
        }
    }
}

private fun FlowContent.matchTable(
    chrome: Chrome,
    rows: List<MatchRow>,
    label: String,
    column: String,
    cell: FlowContent.(MatchRow) -> Unit,
) = div("scroll") {
    table {
        caption("sr") { +label }
        thead {
            tr {
                col("Title")
                col("Library")
                col(column)
                col("Action")
            }
        }
        tbody {
            for (row in rows) {
                tr(flashClass(chrome, row.item.id)) {
                    th {
                        attributes["scope"] = "row"
                        +row.item.title
                        row.item.year?.let { span("muted") { +" ($it)" } }
                    }
                    td { +row.library.name }
                    td { cell(row) }
                    td("nowrap") {
                        a("/admin/library/items/${row.item.id}/match", classes = "small") {
                            attributes["aria-label"] = "Find ${row.item.title} on TMDB"
                            +"Find on TMDB"
                        }
                    }
                }
            }
        }
    }
}

internal fun matchPage(chrome: Chrome, search: MatchSearch): String =
    layout("Match ${search.item.title}", chrome, "/admin/library/unmatched") {
        val item = search.item
        pageHead(
            "Match ${item.title}",
            "Search TMDB for this ${kindWord(item)} and choose its entry, or give the entry's id from its TMDB " +
                "address. The file is ${item.path.orEmpty()}.",
        )
        tabs(chrome, "/admin/library/unmatched", LIBRARY_TABS)
        panel("Search TMDB", id = "search") {
            form(action = "/admin/library/items/${item.id}/match", method = FormMethod.get, classes = "filters") {
                div("field wide") {
                    label {
                        htmlFor = "q"
                        +"Title"
                    }
                    searchInput(name = "q") {
                        id = "q"
                        value = search.query
                        required = true
                    }
                }
                div("field") {
                    label {
                        htmlFor = "year"
                        +"Year"
                    }
                    numberInput(name = "year") {
                        id = "year"
                        value = search.year?.toString().orEmpty()
                        min = "1870"
                        max = "2999"
                    }
                }
                button(type = ButtonType.submit) { +"Search" }
            }
            notice(search.error)
            candidates(chrome, search)
        }
        panel("By TMDB id", id = "by-id") {
            postForm("/admin/library/items/${item.id}/match", chrome.session) {
                label {
                    htmlFor = "tmdb"
                    +"TMDB id"
                }
                +" "
                input(type = InputType.number, name = "tmdb") {
                    id = "tmdb"
                    min = "1"
                    required = true
                }
                +" "
                button(type = ButtonType.submit) { +"Use this id" }
            }
        }
    }

private fun FlowContent.candidates(chrome: Chrome, search: MatchSearch) {
    if (search.error != null) return
    if (search.results.isEmpty()) {
        p("muted") { +"TMDB found nothing for that. Try another title, or no year." }
        return
    }
    div("scroll") {
        table {
            caption("sr") { +"What TMDB found" }
            thead {
                tr {
                    col("Title")
                    col("About")
                    col("Action")
                }
            }
            tbody {
                for (result in search.results) {
                    tr {
                        th {
                            attributes["scope"] = "row"
                            a(tmdbPage(search.item.kind, result.id)) {
                                attributes["rel"] = "noopener noreferrer"
                                +result.title
                            }
                            result.year?.let { span("muted") { +" ($it)" } }
                            br()
                            span("muted small") { +"TMDB ${result.id}" }
                        }
                        td("small") { +result.overview.orEmpty().take(OVERVIEW_CHARS) }
                        td("nowrap") {
                            postForm("/admin/library/items/${search.item.id}/match", chrome.session) {
                                input(type = InputType.hidden, name = "tmdb") { value = result.id.toString() }
                                button(type = ButtonType.submit, classes = "small") {
                                    attributes["aria-label"] = "Match to ${result.title}"
                                    +"Choose"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How much of a candidate's overview the table shows. */
private const val OVERVIEW_CHARS = 240
