package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.library.ItemKind
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.library.LibrarySuggestion
import dev.jamesrich.sofaking.backend.library.TMDB_NOTICE
import kotlinx.html.A
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.VIDEO
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.classes
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.progress
import kotlinx.html.s
import kotlinx.html.select
import kotlinx.html.small
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.video

/**
 * The Library page: each library with its counts and last scan, and the form that adds one. [roots] are the folders
 * libraries may live under and [folders] the choices the form offers; [errors] and [proposed] come back from a
 * refused add.
 */
internal class LibraryView(
    val libraries: List<LibraryRecord>,
    val counts: Map<String, Map<String, Int>>,
    val roots: List<String>,
    val folders: List<String>,
    val scanning: Boolean,
    val errors: List<String> = emptyList(),
    val proposed: Map<String, String>? = null,
    /** The library whose Remove asks for confirmation. */
    val removing: LibraryRecord? = null,
    /** Whether TMDB lookups are on (`SOFA_TMDB_TOKEN` is set, or a token was saved here). */
    val tmdb: Boolean = false,
    /** Whether the page may save a TMDB token: setup is available and the environment does not set one. */
    val tmdbSettable: Boolean = false,
    /** Why the last token was not saved. */
    val tmdbError: String? = null,
    /** Folders whose names say what they hold, each one click from a library. */
    val suggestions: List<LibrarySuggestion> = emptyList(),
)

/** What a library holds, in words: "120 movies", "14 shows, 612 episodes". */
internal fun librarySummary(kind: LibraryKind, counts: Map<String, Int>): String {
    fun n(key: String) = counts[key] ?: 0
    return when (kind) {
        LibraryKind.MOVIES -> plural(n(ItemKind.MOVIE), "movie")
        LibraryKind.SHOWS -> "${plural(n(ItemKind.SHOW), "show")}, ${plural(n(ItemKind.EPISODE), "episode")}"
        LibraryKind.VIDEOS -> plural(n(ItemKind.VIDEO), "video")
    }
}

internal fun libraryPage(chrome: Chrome, view: LibraryView): String = layout("Library", chrome, "/admin/library") {
    val now = chrome.now
    pageHead(
        "Library",
        "Your movies, shows and other videos, from folders mounted into the server. The server only reads them; the app's Library tab plays them, " +
            "direct or transcoded to suit each device. Each library is scanned every six hours and when you ask.",
    )
    panel("Libraries", id = "libraries", aside = if (view.scanning) ({ chip("Scanning", Tone.WARN) }) else null) {
        if (view.libraries.isEmpty()) {
            p("muted mt-0") { +"No library yet. Add one below from a folder under ${view.roots.joinToString(" or ")}." }
            return@panel
        }
        div("scroll") {
            table {
                caption("sr") { +"Libraries" }
                thead {
                    tr {
                        col("Library")
                        col("Folder")
                        col("Holds")
                        col("Last scan")
                        col("Actions")
                    }
                }
                tbody {
                    for (library in view.libraries) {
                        tr(
                            chrome.flash?.takeIf { it.target == library.id }?.let {
                                if (it.tone == Tone.BAD) {
                                    "err-row"
                                } else {
                                    "flash"
                                }
                            },
                        ) {
                            id = "library-${library.id}"
                            th {
                                attributes["scope"] = "row"
                                +library.name
                                span("muted small") { +" · ${library.kind.label}" }
                            }
                            td { code { +library.path } }
                            td("nowrap") { +librarySummary(library.kind, view.counts[library.id].orEmpty()) }
                            td {
                                when (library.scanOk) {
                                    null -> chip("Waiting", Tone.OFF)

                                    true -> {
                                        chip("OK", Tone.OK)
                                        +" "
                                        ago(library.scannedAt, now)
                                    }

                                    false -> {
                                        chip("Folder unavailable", Tone.BAD)
                                        +" "
                                        ago(library.scannedAt, now)
                                        p("muted small mt-0") {
                                            +"The folder was missing or empty, so nothing was changed. Check the mount."
                                        }
                                    }
                                }
                                if (library.scanOk == true && library.scanNote != null) {
                                    span("muted small") {
                                        +" "
                                        +library.scanNote
                                    }
                                }
                            }
                            td("nowrap") {
                                postForm("/admin/library/${library.id}/scan", chrome.session, keepScroll = true) {
                                    button(type = ButtonType.submit, classes = "small") {
                                        attributes["aria-label"] =
                                            "Scan ${library.name} now"
                                        +"Scan now"
                                    }
                                }
                                +" "
                                details("inline") {
                                    summary("small quiet") { +"Rename" }
                                    postForm("/admin/library/${library.id}/rename", chrome.session) {
                                        textInput(name = "name") {
                                            value = library.name
                                            required = true
                                            maxLength = "100"
                                            attributes["aria-label"] =
                                                "New name for ${library.name}"
                                        }
                                        +" "
                                        button(type = ButtonType.submit, classes = "small") { +"Save" }
                                    }
                                }
                                +" "
                                postForm("/admin/library/${library.id}/remove", chrome.session) {
                                    button(type = ButtonType.submit, classes = "small quiet") {
                                        attributes["aria-label"] =
                                            "Remove ${library.name}"
                                        +"Remove"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    view.removing?.let { library ->
        askDialog(
            chrome,
            "Remove ${library.name}?",
            "/admin/library/${library.id}/remove",
            "/admin/library#libraries",
            "Remove",
            danger = true,
        ) {
            p {
                +"The app stops showing it, and its watch progress is forgotten. The files in ${library.path} stay where they are; add the folder again to bring it back."
            }
        }
    }
    panel("Details and artwork", id = "tmdb") {
        if (view.tmdb) {
            p("mt-0") {
                +"Movies and shows get their titles, plots, genres, ratings, cast, posters and backdrops from TMDB after each scan; episodes get their names, plots and stills. A .nfo file beside a video, or local artwork, wins over TMDB. A {tmdb-123} tag in a folder name pins the match."
            }
        } else {
            p("mt-0") {
                +"Details come from file names, .nfo files and local artwork only. Add a TMDB API read access token to fill in plots, ratings, cast, posters and backdrops: it is free from themoviedb.org, under Settings, then API."
            }
        }
        if (view.tmdbSettable) {
            notice(view.tmdbError?.let { "Not saved: $it" })
            postForm("/admin/library/tmdb", chrome.session, classes = "stack mt-12") {
                div("field") {
                    label {
                        htmlFor = "tmdb-token"
                        +(if (view.tmdb) "Replace the TMDB token" else "TMDB API read access token")
                    }
                    passwordInput(name = "token") {
                        id = "tmdb-token"
                        required = true
                        attributes["autocomplete"] =
                            "off"
                        attributes["spellcheck"] = "false"
                    }
                }
                div {
                    button(type = ButtonType.submit, classes = if (view.tmdb) null else "primary") { +"Check and save" }
                }
            }
            p("muted small") {
                +"The server asks TMDB whether it takes the token, keeps it in secrets.json beside the generated tokens, and restarts."
            }
        }
        p("muted small") { +TMDB_NOTICE }
    }
    panel("Add a library", id = "add") {
        if (view.errors.isNotEmpty()) notice("Not added:", view.errors)
        if (view.folders.isEmpty()) {
            p("muted mt-0") {
                +"No folder to add: mount your media under "
                code { +view.roots.joinToString(" or ") }
                +" (read only is enough) and reload this page. "
                a("https://github.com/jamesarich/sofa-king/blob/main/backend/docs/libraries.md") {
                    +"How to lay out the folders"
                }
            }
            return@panel
        }
        if (view.suggestions.isNotEmpty()) {
            p("mt-0") { +"These folders say what they hold:" }
            ul {
                for (suggestion in view.suggestions) {
                    li {
                        code { +suggestion.path.toString() }
                        +" "
                        postForm("/admin/library", chrome.session) {
                            hiddenInput(name = "name") { value = suggestion.name }
                            hiddenInput(name = "kind") { value = suggestion.kind.wire }
                            hiddenInput(name = "path") { value = suggestion.path.toString() }
                            button(type = ButtonType.submit, classes = "small") { +"Add as ${suggestion.kind.label}" }
                        }
                    }
                }
            }
            p("muted small") { +"Or choose any folder below." }
        }
        form(action = "/admin/library", method = FormMethod.post, classes = "stack") {
            hiddenInput(name = "csrf") { value = chrome.session.csrf }
            div("runtime") {
                div("field") {
                    label {
                        htmlFor = "lib-name"
                        +"Name"
                    }
                    textInput(name = "name") {
                        id = "lib-name"
                        value = view.proposed?.get("name") ?: ""
                        required =
                            true
                        maxLength = "100"
                    }
                    span("hint") { +"As the app shows it, such as Movies or Kids' shows" }
                }
                div("field") {
                    label {
                        htmlFor = "lib-kind"
                        +"Contents"
                    }
                    select {
                        id = "lib-kind"
                        name = "kind"
                        for (kind in LibraryKind.entries) {
                            option {
                                value = kind.wire
                                selected =
                                    kind.wire == view.proposed?.get("kind")
                                +kind.label
                            }
                        }
                    }
                    span("hint") {
                        +"How the files are named: Title (Year) for movies, Show/Season 01/Show - S01E02 for shows"
                    }
                }
                div("field") {
                    label {
                        htmlFor = "lib-path"
                        +"Folder"
                    }
                    select {
                        id = "lib-path"
                        name = "path"
                        for (folder in view.folders) {
                            option {
                                value = folder
                                selected =
                                    folder == view.proposed?.get("path")
                                +folder
                            }
                        }
                    }
                    span("hint") { +"A folder under ${view.roots.joinToString(" or ")} that no other library uses" }
                }
            }
            div("actions") { button(type = ButtonType.submit, classes = "primary") { +"Add and scan" } }
        }
    }
}
