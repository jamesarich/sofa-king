package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.library.LibrarySuggestion
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.form

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
