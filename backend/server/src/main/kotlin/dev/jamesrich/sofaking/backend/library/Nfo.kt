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
    /** The role each of the [cast] plays, by name, for those the `.nfo` gives one. */
    val roles: Map<String, String> = emptyMap(),
    val directors: List<String> = emptyList(),
    /** The collection a movie's `<set>` names. */
    val collection: String? = null,
    val tmdbId: Int? = null,
    val tvdbId: Int? = null,
    val imdbId: String? = null,
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

/** The collection `<set>` names: Kodi writes the name in a `<name>` inside it, older tools as the set's own text. */
private fun Element.collection(): String? =
    child("set")?.let { set -> set.child("name") ?: set.takeIf { it.children().isEmpty() } }
        ?.textContent?.trim()?.ifEmpty { null }

/** The first [CAST_LIMIT] `<actor>`s that have a name, each with the role it gives them. */
private fun Element.actors(): List<Pair<String, String?>> = children("actor").mapNotNull { actor ->
    fun text(name: String): String? = actor.child(name)?.textContent?.trim()?.ifEmpty { null }
    text("name")?.let { it to text("role") }
}.take(CAST_LIMIT)

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
    val ids = root.providerIds()
    val actors = root.actors()
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
        cast = actors.map { it.first },
        roles = actors.mapNotNull { (name, role) -> role?.let { name to it } }.toMap(),
        directors = all("director"),
        collection = root.collection(),
        tmdbId = ids.tmdb,
        tvdbId = ids.tvdb,
        imdbId = ids.imdb,
    )
}

/** The provider ids an nfo names, from `<uniqueid>` first and then the older single tags. */
private fun Element.providerIds(): ProviderIds {
    fun text(name: String): String? = child(name)?.textContent?.trim()?.ifEmpty { null }
    return ProviderIds(
        tmdb = uniqueId("tmdb")?.toIntOrNull() ?: text("tmdbid")?.toIntOrNull(),
        tvdb = uniqueId("tvdb")?.toIntOrNull() ?: text("tvdbid")?.toIntOrNull(),
        imdb = (uniqueId("imdb") ?: text("imdbid") ?: text("imdb_id") ?: text("id"))
            ?.lowercase()?.takeIf { IMDB_ID.matches(it) },
    )
}

/** An IMDb title id: `tt` and digits. Kodi writes it in `<id>` too, where other scrapers write their own ids. */
internal val IMDB_ID = Regex("tt\\d{5,10}")

/** The text of the `<uniqueid type="...">` of [type], ignoring case. */
private fun Element.uniqueId(type: String): String? = children("uniqueid").firstOrNull {
    it.getAttribute("type").equals(type, ignoreCase = true)
}?.textContent?.trim()?.ifEmpty { null }

private fun Element.children(name: String? = null): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull {
        nodes.item(it) as? Element
    }.filter { name == null || it.tagName == name }
}

private fun Element.child(name: String): Element? = children(name).firstOrNull()
