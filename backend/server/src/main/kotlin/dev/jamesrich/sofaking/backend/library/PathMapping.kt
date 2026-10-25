package dev.jamesrich.sofaking.backend.library

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * A folder as another program names it ([from]) and as this server mounts it ([to]), for a Sonarr or Radarr container
 * that sees the media under another path. `SOFA_LIBRARY_PATH_MAP` sets them.
 */
data class PathMapping(val from: String, val to: Path)

/** `SOFA_LIBRARY_PATH_MAP`: `from=to` pairs separated by commas, such as `/tv=/media/tv,/movies=/media/movies`. */
internal fun pathMappings(value: String?): List<PathMapping> =
    value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { pair ->
        val from = pair.substringBefore('=', "").trim().trimEnd('/', '\\')
        val to = pair.substringAfter('=', "").trim()
        require(from.isNotEmpty() && to.startsWith("/")) {
            "SOFA_LIBRARY_PATH_MAP takes from=to pairs separated by commas, each to an absolute folder: $pair"
        }
        PathMapping(from, Path.of(to).normalize())
    }

/**
 * [path], as another program named it, in this server's terms: the first of [mappings] whose folder holds it applied.
 * Null when it is not an absolute path after that.
 */
internal fun localPath(path: String, mappings: List<PathMapping>): Path? = try {
    val trimmed = path.trim()
    val mapped = mappings.firstNotNullOfOrNull { mapping ->
        val rest = trimmed.removePrefix(mapping.from)
        // A Windows host names folders with backslashes.
        val within = rest.length < trimmed.length && (rest.isEmpty() || rest[0] == '/' || rest[0] == '\\')
        if (within) mapping.to.resolve(rest.replace('\\', '/').trimStart('/')) else null
    } ?: Path.of(trimmed).takeIf { trimmed.startsWith("/") }
    mapped?.normalize()
} catch (_: InvalidPathException) {
    null
}

/**
 * The libraries a change at [path] concerns: the one whose folder holds it, or every library inside it when it names
 * a folder above them.
 */
internal fun librariesAt(path: Path, libraries: List<LibraryRecord>): List<LibraryRecord> =
    libraries.filter { library ->
        val folder = Path.of(library.path).normalize()
        path.startsWith(folder) || folder.startsWith(path)
    }
