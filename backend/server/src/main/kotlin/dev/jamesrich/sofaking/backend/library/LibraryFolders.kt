package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraries
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** A folder whose name says what it holds, offered as a library to add with one click. */
data class LibrarySuggestion(val name: String, val kind: LibraryKind, val path: Path)

/** What a folder named so holds, by its name in lower case with separators as spaces. */
private val SUGGESTED_KINDS = mapOf(
    "movies" to LibraryKind.MOVIES, "movie" to LibraryKind.MOVIES, "films" to LibraryKind.MOVIES,
    "film" to LibraryKind.MOVIES,
    "tv" to LibraryKind.SHOWS, "tv shows" to LibraryKind.SHOWS, "shows" to LibraryKind.SHOWS,
    "series" to LibraryKind.SHOWS,
    "tv series" to LibraryKind.SHOWS, "television" to LibraryKind.SHOWS,
    "videos" to LibraryKind.VIDEOS, "home videos" to LibraryKind.VIDEOS, "music videos" to LibraryKind.VIDEOS,
)

/** The folders under [roots] a library may be added from, and the ones whose names suggest one. */
class LibraryFolders internal constructor(private val store: BackendStore, private val roots: List<MediaRoot>) {
    /**
     * The folders a new library can use: each root and its subfolders two levels down, skipping hidden and NAS
     * housekeeping folders and those another library already uses.
     */
    suspend fun choices(): List<Path> {
        val used = store.libraries().mapTo(HashSet()) { it.path }
        // Absolute, as create stores them, so a relative root still drops the folders in use.
        return roots.flatMap { root -> below(root.root.toAbsolutePath().normalize(), 2) }
            .filter { it.toString() !in used }
    }

    /**
     * The unused folders right under a root whose names say what they hold, such as `Movies` or `TV Shows`, named after
     * the folder; one under the recordings folder carries the root's label too, as `TV (Recordings)`.
     */
    suspend fun suggestions(): List<LibrarySuggestion> {
        val parents = roots.associateBy { it.root.toAbsolutePath().normalize() }
        return choices().mapNotNull { folder ->
            val root = parents[folder.parent] ?: return@mapNotNull null
            val name = folder.fileName.toString()
            val key = name.lowercase().replace(Regex("[_.\\-]+"), " ").replace(Regex("\\s+"), " ").trim()
            val kind = SUGGESTED_KINDS[key] ?: return@mapNotNull null
            LibrarySuggestion(if (root == roots.first()) name else "$name (${root.label})", kind, folder)
        }
    }

    private fun below(root: Path, depth: Int): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val below = if (depth == 0) {
            emptyList()
        } else {
            try {
                Files.list(root).use { stream ->
                    stream.filter {
                        Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) &&
                            !skippedFolder(it.fileName.toString()) &&
                            extraFolderKind(it.fileName.toString()) == null
                    }.sorted().toList()
                }.flatMap { below(it, depth - 1) }
            } catch (_: IOException) {
                emptyList()
            }
        }
        return listOf(root.normalize()) + below
    }

    /** [path] as a normalized absolute folder inside one of [roots], or a 400 naming what is wrong. */
    internal fun allowed(path: String): Path {
        val candidate = try {
            Path.of(path.trim()).toAbsolutePath().normalize()
        } catch (_: Exception) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Not a folder path")
        }
        val root = roots.firstOrNull { candidate.startsWith(it.root.toAbsolutePath().normalize()) }
            ?: throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_REQUEST",
                "A library folder must be inside ${roots.joinToString(" or ") { it.root.toString() }}",
            )
        if (!Files.isDirectory(candidate)) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "No folder at that path")
        }
        // A link inside the root must not lead the scan outside it.
        val real = candidate.toRealPath()
        if (!real.startsWith(root.root.toRealPath())) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "That folder links outside ${root.root}")
        }
        return candidate
    }
}
