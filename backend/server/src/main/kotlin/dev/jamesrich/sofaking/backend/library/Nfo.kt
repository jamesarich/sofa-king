package dev.jamesrich.sofaking.backend.library

import org.w3c.dom.Element
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a Kodi `.nfo` sidecar says about a movie (`<movie>`), a show (`<tvshow>`) or an episode (`<episodedetails>`).
 * Every field is optional; the scan falls back to the file name for what is missing.
 */
internal data class Nfo(
    val title: String? = null,
    val sortTitle: String? = null,
    val year: Int? = null,
    val plot: String? = null,
    val tagline: String? = null,
    val runtimeMinutes: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val aired: String? = null,
    val genres: List<String> = emptyList(),
    val studio: String? = null,
    val contentRating: String? = null,
    val rating: Double? = null,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val tmdbId: Int? = null,
)

/** The largest sidecar a scan reads; a real one is a few kilobytes. */
private const val NFO_LIMIT = 1L shl 20

private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
    // A sidecar comes from the operator's disk, but no entity or DTD it names is ever fetched or expanded.
    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    isXIncludeAware = false
    isExpandEntityReferences = false
    isNamespaceAware = false
}

/** The `.nfo` at [path], or null when it is missing, too large or not XML (some tools write a bare URL instead). */
internal fun readNfo(path: Path): Nfo? = try {
    if (!Files.isRegularFile(path) || Files.size(path) > NFO_LIMIT) null else Files.newInputStream(path).use(::parseNfo)
} catch (_: Exception) {
    null
}

internal fun parseNfo(input: InputStream): Nfo? {
    val root = try {
        factory.newDocumentBuilder().apply { setErrorHandler(null) }.parse(input).documentElement
    } catch (_: Exception) {
        return null
    }
    if (root.tagName !in setOf("movie", "tvshow", "episodedetails", "musicvideo")) return null
    fun text(name: String): String? = root.child(name)?.textContent?.trim()?.ifEmpty { null }
    fun all(name: String): List<String> = root.children(name).mapNotNull { it.textContent?.trim()?.ifEmpty { null } }
    val rating = text("rating")?.toDoubleOrNull()
        ?: root.child("ratings")?.children("rating")?.let { ratings ->
            (
                ratings.firstOrNull { it.getAttribute("default") == "true" }
                    ?: ratings.firstOrNull()
                )
        }
            ?.child("value")?.textContent?.trim()?.toDoubleOrNull()
    val aired = text("aired") ?: text("premiered")
    return Nfo(
        title = text("title"),
        sortTitle = text("sorttitle"),
        year = text("year")?.toIntOrNull() ?: yearOf(aired),
        plot = text("plot") ?: text("outline"),
        tagline = text("tagline"),
        runtimeMinutes = text("runtime")?.toIntOrNull(),
        season = text("season")?.toIntOrNull(),
        episode = text("episode")?.toIntOrNull(),
        aired = aired,
        genres = all("genre"),
        studio = text("studio"),
        contentRating = text("mpaa") ?: text("certification"),
        rating = rating,
        cast = root.children("actor").mapNotNull {
            it.child("name")?.textContent?.trim()?.ifEmpty { null }
        }.take(CAST_LIMIT),
        directors = all("director"),
        tmdbId =
        root.children("uniqueid").firstOrNull {
            it.getAttribute("type").equals("tmdb", ignoreCase = true)
        }?.textContent?.trim()?.toIntOrNull()
            ?: text("tmdbid")?.toIntOrNull(),
    )
}

private fun Element.children(name: String): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == name }
}

private fun Element.child(name: String): Element? = children(name).firstOrNull()
