package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.library.ItemKind
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.library.LibrarySuggestion
import kotlinx.html.a
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
    /** Whether a fanart.tv key is set (`SOFA_FANART_KEY`, or a key saved here). */
    val fanart: Boolean = false,
    /** Whether the page may save a fanart.tv key: setup is available and the environment does not set one. */
    val fanartSettable: Boolean = false,
    /** Why the last fanart.tv key was not saved. */
    val fanartError: String? = null,
    /** Folders whose names say what they hold, each one click from a library. */
    val suggestions: List<LibrarySuggestion> = emptyList(),
)

/** What a library holds, in words: "120 movies", "14 shows, 612 episodes", with its extras when it has any. */
internal fun librarySummary(kind: LibraryKind, counts: Map<String, Int>): String {
    fun n(key: String) = counts[key] ?: 0
    val main = when (kind) {
        LibraryKind.MOVIES -> plural(n(ItemKind.MOVIE), "movie")

        LibraryKind.SHOWS -> "${plural(n(ItemKind.SHOW), "show")}, ${plural(n(ItemKind.EPISODE), "episode")}"

        LibraryKind.VIDEOS -> plural(n(ItemKind.VIDEO), "video")

        LibraryKind.MUSIC ->
            listOf(
                plural(n(ItemKind.ARTIST), "artist"),
                plural(n(ItemKind.ALBUM), "album"),
                plural(n(ItemKind.TRACK), "track"),
            )
                .joinToString(", ")
    }
    return if (n(ItemKind.EXTRA) > 0) "$main, ${plural(n(ItemKind.EXTRA), "extra")}" else main
}
