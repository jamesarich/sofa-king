package dev.jamesrich.sofaking.backend.library

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val ART_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

/** The digits a season's and an episode's number are padded to, so that they sort as numbers. */
private const val SEASON_SORT_DIGITS = 6
private const val EPISODE_SORT_DIGITS = 5

/** The numbers and what follows them in a loose episode's name, leaving its show's name: `Show S01E02 Title`. */
private val LOOSE_NUMBERS = Regex("""(?i)[\s._]+s\d{1,4}e\d{1,4}.*$""")

/** The movie in [file], named by its folder when that has a year, else by the file. */
internal fun movieItem(libraryId: String, root: Path, file: FoundFile): LibraryItemRecord {
    val path = root.resolve(file.relative)
    val folder = path.parent.takeIf { it != root }
    val stem = path.fileName.toString().substringBeforeLast('.')
    val named = folder?.let { titleYear(it.fileName.toString()) }?.takeIf { it.year != null } ?: titleYear(stem)
    val nfo = readNfo(path.resolveSibling("$stem.nfo")) ?: folder?.let { readNfo(it.resolve("movie.nfo")) }
    val title = nfo?.title ?: named.title
    val tag = tmdbIdIn(stem) ?: folder?.let { tmdbIdIn(it.fileName.toString()) }
    return LibraryItemRecord(
        id = itemId(libraryId, "file:${file.relative}"),
        libraryId = libraryId, kind = ItemKind.MOVIE, path = file.relative,
        title = title,
        sortTitle = sortTitle(nfo?.sortTitle ?: title),
        year = nfo?.year ?: named.year, aired = nfo?.aired, overview = nfo?.plot,
        durationMs = nfo?.runtimeMinutes?.let(::minutesMs), size = file.size, modifiedAt = file.modifiedAt,
        details = nfo?.details(tag) ?: tagged(tag),
        poster = art(
            root,
            path.resolveSibling("$stem-poster"),
            folder?.resolve("poster"),
            folder?.resolve("folder"),
            folder?.resolve("cover"),
        ),
        backdrop = art(
            root,
            path.resolveSibling("$stem-fanart"),
            folder?.resolve("fanart"),
            folder?.resolve("backdrop"),
        ),
        addedAt = "",
    )
}

/** The video in [file] of an Other videos library, named by the file. */
internal fun videoItem(libraryId: String, root: Path, file: FoundFile): LibraryItemRecord {
    val path = root.resolve(file.relative)
    val stem = path.fileName.toString().substringBeforeLast('.')
    val nfo = readNfo(path.resolveSibling("$stem.nfo"))
    val title = nfo?.title ?: titleWithYear(stem)
    return LibraryItemRecord(
        id = itemId(libraryId, "file:${file.relative}"),
        libraryId = libraryId, kind = ItemKind.VIDEO, path = file.relative,
        title = title,
        sortTitle = sortTitle(nfo?.sortTitle ?: title),
        year = nfo?.year, aired = nfo?.aired, overview = nfo?.plot,
        size = file.size, modifiedAt = file.modifiedAt, details = nfo?.details(),
        poster = art(
            root,
            path.resolveSibling("$stem-poster"),
            path.resolveSibling("$stem-thumb"),
            path.resolveSibling(stem),
        ),
        addedAt = "",
    )
}

/** The title [stem] names, with its year in parentheses when it has one; the stem itself when it names nothing. */
internal fun titleWithYear(stem: String): String =
    titleYear(stem).let { if (it.year != null) "${it.title} (${it.year})" else it.title }.ifEmpty { stem }

/**
 * The shows, seasons and episodes of a TV library at [root]. Shows are its top folders (or the name before the
 * numbers of a loose file), seasons their season folders.
 */
internal class ShowItems(private val libraryId: String, private val root: Path) {
    private val shows = LinkedHashMap<String, LibraryItemRecord>()
    private val seasons = LinkedHashMap<String, LibraryItemRecord>()
    private val episodes = ArrayList<LibraryItemRecord>()

    /** The shows, then the seasons, then the episodes, each in the order their first file came. */
    fun items(): List<LibraryItemRecord> = shows.values + seasons.values + episodes

    fun add(file: FoundFile) {
        val segments = file.relative.split('/')
        val stem = segments.last().substringBeforeLast('.')
        // A loose file names its show before its numbers: `Show - S01E02.mkv`.
        val showKey =
            if (segments.size > 1) segments.first() else stem.substringBefore(" - ").replace(LOOSE_NUMBERS, "").trim()
        val showFolder = if (segments.size > 1) root.resolve(segments.first()) else null
        val show = shows.getOrPut(showKey) { show(showKey, showFolder) }
        val path = root.resolve(file.relative)
        val parsed = episodeName(stem)
        val nfo = readNfo(path.resolveSibling("$stem.nfo"))
        val folderSeason = segments.drop(1).dropLast(1).firstNotNullOfOrNull(::seasonOfFolder)
        val number = nfo?.season ?: folderSeason ?: parsed.season ?: parsed.aired?.year
        val season = number?.let {
            seasons.getOrPut("$showKey/$it") { season("$showKey/$it", it, show, path.parent, showFolder) }
        }
        episodes += episode(file, parsed, nfo, number, season?.id ?: show.id)
    }

    private fun show(key: String, folder: Path?): LibraryItemRecord {
        val nfo = folder?.let { readNfo(it.resolve("tvshow.nfo")) }
        val named = titleYear(key)
        val title = nfo?.title ?: named.title
        val tag = tmdbIdIn(key)
        return LibraryItemRecord(
            id = itemId(libraryId, "show:$key"), libraryId = libraryId, kind = ItemKind.SHOW, title = title,
            sortTitle = sortTitle(nfo?.sortTitle ?: title),
            year = nfo?.year ?: named.year, aired = nfo?.aired, overview = nfo?.plot,
            details = nfo?.details(tag) ?: tagged(tag),
            poster = folder?.let { art(root, it.resolve("poster"), it.resolve("folder"), it.resolve("cover")) },
            backdrop = folder?.let { art(root, it.resolve("fanart"), it.resolve("backdrop")) },
            addedAt = "",
        )
    }

    /** Season [number] of [show], keyed [key]; its poster is in [showFolder], or in a season [folder]. */
    private fun season(
        key: String,
        number: Int,
        show: LibraryItemRecord,
        folder: Path,
        showFolder: Path?,
    ): LibraryItemRecord {
        val seasonFolder = folder.takeIf { it != root && seasonOfFolder(it.fileName.toString()) != null }
        val padded = number.toString().padStart(2, '0')
        val showsPoster = if (number == 0) "season-specials-poster" else "season$padded-poster"
        return LibraryItemRecord(
            id = itemId(libraryId, "season:$key"),
            libraryId = libraryId, kind = ItemKind.SEASON, parentId = show.id,
            title = if (number == 0) "Specials" else "Season $number",
            sortTitle = padded.padStart(SEASON_SORT_DIGITS, '0'), season = number,
            poster = art(
                root,
                showFolder?.resolve(showsPoster),
                seasonFolder?.resolve("poster"),
                seasonFolder?.resolve("folder"),
            ),
            addedAt = "",
        )
    }

    private fun episode(
        file: FoundFile,
        parsed: EpisodeName,
        nfo: Nfo?,
        season: Int?,
        parentId: String,
    ): LibraryItemRecord {
        val path = root.resolve(file.relative)
        val stem = path.fileName.toString().substringBeforeLast('.')
        val aired = nfo?.aired ?: parsed.aired?.toString()
        val title = nfo?.title ?: parsed.title ?: when {
            parsed.episode != null -> "Episode ${parsed.episode}"
            aired != null -> aired
            else -> stem
        }
        val order = listOfNotNull(
            parsed.episode?.toString()?.padStart(EPISODE_SORT_DIGITS, '0'),
            aired,
        ).joinToString(" ")
            .ifEmpty { sortTitle(title) }
        return LibraryItemRecord(
            id = itemId(libraryId, "file:${file.relative}"), libraryId = libraryId, kind = ItemKind.EPISODE,
            parentId = parentId,
            path = file.relative, title = title, sortTitle = order,
            year = yearOf(aired),
            season = season,
            episode = nfo?.episode ?: parsed.episode, episodeEnd = parsed.episodeEnd, aired = aired,
            overview = nfo?.plot,
            durationMs = nfo?.runtimeMinutes?.let(::minutesMs),
            size = file.size, modifiedAt = file.modifiedAt, details = nfo?.details(),
            poster = art(root, path.resolveSibling("$stem-thumb"), path.resolveSibling(stem)),
            addedAt = "",
        )
    }
}

private fun minutesMs(minutes: Int): Long = TimeUnit.MINUTES.toMillis(minutes.toLong())

/** The first of [bases] that exists with an image extension, relative to [root]; null when none does. */
private fun art(root: Path, vararg bases: Path?): String? {
    for (base in bases.filterNotNull()) {
        val found = ART_EXTENSIONS.map { base.resolveSibling("${base.fileName}.$it") }
            .firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        if (found != null) return root.relativize(found).joinToString("/")
    }
    return null
}

private fun Nfo.details(tagged: Int? = null): ItemDetails = ItemDetails(
    tagline, genres, studio, contentRating, rating, cast, directors,
    tmdbId ?: tagged, nfo = true,
)

/** No `.nfo`, but maybe a TMDB id in the name. */
private fun tagged(id: Int?): ItemDetails? = id?.let { ItemDetails(tmdbId = it) }
