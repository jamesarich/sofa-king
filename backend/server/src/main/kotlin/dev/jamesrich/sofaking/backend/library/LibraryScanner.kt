package dev.jamesrich.sofaking.backend.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The deepest folder a scan descends to below a library's folder. */
private const val MAX_DEPTH = 10

/**
 * One video file found under a library folder; [relative] uses `/` on every platform. An [extra] belongs to the item
 * in the folder [owner] (relative, empty for the library folder), the one named [ownerStem] when its name says.
 */
internal data class FoundFile(
    val relative: String,
    val size: Long,
    val modifiedAt: String,
    val extra: ExtraKind? = null,
    val owner: String? = null,
    val ownerStem: String? = null,
)

/**
 * What a scan decided: rows new or changed since the last scan to write whole, ids newly missing,
 * ids gone long enough to delete.
 */
class ScanPlan(
    val save: List<LibraryItemRecord>,
    val missing: List<String>,
    val delete: List<String>,
    val result: ScanResult,
)

/** The stable id of the item [key] in library [libraryId]: the same on every scan and every server. */
internal fun itemId(libraryId: String, key: String): String =
    UUID.nameUUIDFromBytes("$libraryId\u0000$key".toByteArray()).toString()

/**
 * Lists a library folder and turns its files into items, reading names, `.nfo` sidecars and local art, and probing
 * new or changed files with [probe] ([probeParallelism] at a time). Files keep their row while they are missing for
 * less than [missingGrace], so a disk that is briefly unmounted keeps its ids and watch state.
 */
class LibraryScanner(
    private val probe: suspend (Path) -> MediaInfo?,
    private val probeParallelism: Int = 2,
    private val missingGrace: Duration = Duration.ofDays(7),
) {
    suspend fun scan(library: LibraryRecord, root: Path, existing: List<LibraryItemRecord>, now: Instant): ScanPlan {
        val files = withContext(Dispatchers.IO) { listFiles(root) }
        val present = existing.count { it.missingSince == null && it.path != null }
        // An empty or absent folder that held files is an unmounted disk, not a library whose every file was deleted.
        if (files == null || (files.isEmpty() && present > 0)) {
            return ScanPlan(emptyList(), emptyList(), emptyList(), ScanResult(0, 0, 0, 0, 0, unavailable = true))
        }
        val byId = existing.associateBy { it.id }
        val (extraFiles, mainFiles) = files.partition { it.extra != null }
        val built = withContext(Dispatchers.IO) {
            val main = when (library.kind) {
                LibraryKind.MOVIES -> mainFiles.map { movie(library.id, root, it) }
                LibraryKind.SHOWS -> shows(library.id, root, mainFiles)
                LibraryKind.VIDEOS -> mainFiles.map { video(library.id, root, it) }
            }
            main + extras(library, main, extraFiles)
        }
        val added = AtomicInteger()
        val updated = AtomicInteger()
        val semaphore = Semaphore(probeParallelism)
        val items = coroutineScope {
            built.map { item ->
                async {
                    val old = byId[item.id]
                    val unchanged =
                        old != null && old.size == item.size && old.modifiedAt == item.modifiedAt && old.media != null
                    val media = when {
                        item.path == null -> null
                        unchanged -> old.media
                        else -> semaphore.withPermit { probe(root.resolve(item.path)) }
                    }
                    if (item.path != null) {
                        if (old == null) {
                            added.incrementAndGet()
                        } else if (!unchanged) {
                            updated.incrementAndGet()
                        }
                    }
                    item.copy(
                        media = media,
                        durationMs = media?.durationMs ?: item.durationMs,
                        addedAt =
                        old?.addedAt ?: item.addedAt.ifEmpty { now.toString() },
                    )
                }
            }.awaitAll()
        }
        // A show or season takes the newest add time of what it holds, so a new episode brings
        // its show up in Recently added.
        val parents = items.associate { it.id to it.parentId }
        val newest = HashMap<String, String>()
        for (item in items) {
            // An extra found later does not bring its movie back up.
            if (item.kind == ItemKind.EXTRA) continue
            var parent = item.parentId
            while (parent != null) {
                if ((newest[parent] ?: "") < item.addedAt) newest[parent] = item.addedAt
                parent = parents[parent]
            }
        }
        val saved = items.map { item -> newest[item.id]?.let { item.copy(addedAt = it) } ?: item }
        val seen = saved.mapTo(HashSet()) { it.id }
        val gone = existing.filter { it.id !in seen }
        val missing = gone.filter { it.missingSince == null }.map { it.id }
        val delete = gone.filter { old ->
            old.missingSince?.let { Duration.between(Instant.parse(it), now) >= missingGrace } ==
                true
        }.map { it.id }
        // An unchanged row is not rewritten, so a rescan of a big library is not one long write
        // on the database's only thread.
        return ScanPlan(
            saved.filter {
                it != byId[it.id]
            },
            missing,
            delete,
            ScanResult(
                added.get(),
                updated.get(),
                missing.count { byId[it]?.path != null },
                delete.size,
                built.count { it.path != null },
            ),
        )
    }

    /** Every video under [root] with its size and change time, or null when [root] is not a readable folder. */
    internal fun listFiles(root: Path): List<FoundFile>? {
        if (!Files.isDirectory(root)) return null
        val found = ArrayList<FoundFile>()
        try {
            Files.walkFileTree(
                root,
                emptySet(),
                MAX_DEPTH + 1,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (dir != root && skippedFolder(dir.fileName.toString())) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // Links are not followed, so nothing outside the library folder is ever listed.
                        if (attrs.isRegularFile && isVideoFile(file.fileName.toString())) {
                            found +=
                                found(
                                    root.relativize(file).map {
                                        it.toString()
                                    },
                                    attrs.size(),
                                    attrs.lastModifiedTime().toInstant().toString(),
                                )
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (_: IOException) {
            return null
        }
        return found.sortedBy { it.relative }
    }

    /** A file at [segments] under the library folder, an extra when a folder above it or its name says so. */
    private fun found(segments: List<String>, size: Long, modifiedAt: String): FoundFile {
        val relative = segments.joinToString("/")
        val folders = segments.dropLast(1)
        val inFolder = folders.indices.lastOrNull { extraFolderKind(folders[it]) != null }
        if (inFolder !=
            null
        ) {
            return FoundFile(
                relative,
                size,
                modifiedAt,
                extraFolderKind(folders[inFolder]),
                folders.take(inFolder).joinToString("/"),
            )
        }
        val suffix = extraSuffix(segments.last()) ?: return FoundFile(relative, size, modifiedAt)
        return FoundFile(relative, size, modifiedAt, suffix.first, folders.joinToString("/"), suffix.second)
    }

    /**
     * The [files] that are extras, as items of the movie or show they belong to among [main]: a movie's are in its
     * folder (or named for it beside it), a show's anywhere in its folder. Extras nothing claims, such as those of a
     * folder of loose movies, and every extra in an Other videos library, are left out.
     */
    private fun extras(
        library: LibraryRecord,
        main: List<LibraryItemRecord>,
        files: List<FoundFile>,
    ): List<LibraryItemRecord> {
        if (library.kind == LibraryKind.VIDEOS) return emptyList()
        val movies = main.filter { it.kind == ItemKind.MOVIE }.groupBy { it.path!!.substringBeforeLast('/', "") }
        val shows = main.filter { it.kind == ItemKind.SHOW }.mapTo(HashSet()) { it.id }
        return files.mapNotNull { file ->
            val owner = file.owner ?: return@mapNotNull null
            val kind = file.extra ?: return@mapNotNull null
            val parent = when (library.kind) {
                LibraryKind.MOVIES -> movies[owner].orEmpty().let { here ->
                    file.ownerStem?.let { stem ->
                        here.firstOrNull {
                            it.path!!.substringAfterLast('/').substringBeforeLast('.') ==
                                stem
                        }
                    }
                        ?: here.firstOrNull()?.takeIf { owner.isNotEmpty() || here.size == 1 }
                }?.id

                else -> owner.takeIf {
                    it.isNotEmpty()
                }?.let { itemId(library.id, "show:${it.substringBefore('/')}") }?.takeIf {
                    it in
                        shows
                }
            } ?: return@mapNotNull null
            val stem = file.relative.substringAfterLast('/').substringBeforeLast('.')
            val title = if (file.ownerStem !=
                null
            ) {
                kind.label
            } else {
                titleYear(stem).let {
                    if (it.year !=
                        null
                    ) {
                        "${it.title} (${it.year})"
                    } else {
                        it.title
                    }
                }.ifEmpty { stem }
            }
            LibraryItemRecord(
                id = itemId(
                    library.id,
                    "file:${file.relative}",
                ),
                libraryId = library.id, kind = ItemKind.EXTRA, parentId = parent,
                path = file.relative, title = title,
                sortTitle = "${kind.ordinal} ${sortTitle(
                    title,
                )}",
                size = file.size,
                modifiedAt = file.modifiedAt, details = ItemDetails(extra = kind.wire), addedAt = "",
            )
        }
    }

    private fun movie(libraryId: String, root: Path, file: FoundFile): LibraryItemRecord {
        val path = root.resolve(file.relative)
        val folder = path.parent.takeIf { it != root }
        val stem = path.fileName.toString().substringBeforeLast('.')
        val named = folder?.let { titleYear(it.fileName.toString()) }?.takeIf { it.year != null } ?: titleYear(stem)
        val nfo = readNfo(path.resolveSibling("$stem.nfo")) ?: folder?.let { readNfo(it.resolve("movie.nfo")) }
        val title = nfo?.title ?: named.title
        return LibraryItemRecord(
            id = itemId(
                libraryId,
                "file:${file.relative}",
            ),
            libraryId = libraryId, kind = ItemKind.MOVIE, path = file.relative,
            title = title,
            sortTitle = sortTitle(
                nfo?.sortTitle ?: title,
            ),
            year = nfo?.year ?: named.year, aired = nfo?.aired, overview = nfo?.plot,
            durationMs = nfo?.runtimeMinutes?.let { it * 60_000L }, size = file.size, modifiedAt = file.modifiedAt,
            details = (tmdbIdIn(stem) ?: folder?.let { tmdbIdIn(it.fileName.toString()) }).let { tag ->
                nfo?.details(tag)
                    ?: tagged(tag)
            },
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

    private fun video(libraryId: String, root: Path, file: FoundFile): LibraryItemRecord {
        val path = root.resolve(file.relative)
        val stem = path.fileName.toString().substringBeforeLast('.')
        val nfo = readNfo(path.resolveSibling("$stem.nfo"))
        val title =
            nfo?.title
                ?: titleYear(stem).let {
                    if (it.year != null) {
                        "${it.title} (${it.year})"
                    } else {
                        it.title
                    }
                }.ifEmpty { stem }
        return LibraryItemRecord(
            id = itemId(
                libraryId,
                "file:${file.relative}",
            ),
            libraryId = libraryId, kind = ItemKind.VIDEO, path = file.relative,
            title = title,
            sortTitle = sortTitle(
                nfo?.sortTitle ?: title,
            ),
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

    /**
     * Shows are the library's top folders (or the name before the numbers of a loose
     * file), seasons their season folders.
     */
    private fun shows(libraryId: String, root: Path, files: List<FoundFile>): List<LibraryItemRecord> {
        val items = ArrayList<LibraryItemRecord>()
        val shows = LinkedHashMap<String, LibraryItemRecord>()
        val seasons = LinkedHashMap<String, LibraryItemRecord>()
        for (file in files) {
            val segments = file.relative.split('/')
            val path = root.resolve(file.relative)
            val stem = segments.last().substringBeforeLast('.')
            val parsed = episodeName(stem)
            // A loose file names its show before its numbers: `Show - S01E02.mkv`.
            val showKey = if (segments.size > 1) {
                segments.first()
            } else {
                stem.substringBefore(" - ").replace(Regex("""(?i)[\s._]+s\d{1,4}e\d{1,4}.*$"""), "").trim()
            }
            val showFolder = if (segments.size > 1) root.resolve(segments.first()) else null
            val show = shows.getOrPut(showKey) {
                val nfo = showFolder?.let { readNfo(it.resolve("tvshow.nfo")) }
                val named = titleYear(showKey)
                val title = nfo?.title ?: named.title
                LibraryItemRecord(
                    id = itemId(libraryId, "show:$showKey"), libraryId = libraryId, kind = ItemKind.SHOW, title = title,
                    sortTitle = sortTitle(
                        nfo?.sortTitle ?: title,
                    ),
                    year = nfo?.year ?: named.year, aired = nfo?.aired, overview = nfo?.plot,
                    details = tmdbIdIn(showKey).let { tag -> nfo?.details(tag) ?: tagged(tag) },
                    poster = showFolder?.let {
                        art(root, it.resolve("poster"), it.resolve("folder"), it.resolve("cover"))
                    },
                    backdrop = showFolder?.let { art(root, it.resolve("fanart"), it.resolve("backdrop")) },
                    addedAt = "",
                )
            }
            val folderSeason = segments.drop(1).dropLast(1).firstNotNullOfOrNull(::seasonOfFolder)
            val nfo = readNfo(path.resolveSibling("$stem.nfo"))
            val seasonNumber = nfo?.season ?: folderSeason ?: parsed.season ?: parsed.aired?.year
            val season = seasonNumber?.let { number ->
                seasons.getOrPut("$showKey/$number") {
                    val seasonFolder = path.parent.takeIf {
                        it != root && seasonOfFolder(it.fileName.toString()) != null
                    }
                    val padded = number.toString().padStart(2, '0')
                    LibraryItemRecord(
                        id = itemId(
                            libraryId,
                            "season:$showKey/$number",
                        ),
                        libraryId = libraryId, kind = ItemKind.SEASON, parentId = show.id,
                        title = if (number == 0) {
                            "Specials"
                        } else {
                            "Season $number"
                        },
                        sortTitle = padded.padStart(6, '0'), season = number,
                        poster = art(
                            root,
                            showFolder?.resolve(
                                if (number == 0) {
                                    "season-specials-poster"
                                } else {
                                    "season$padded-poster"
                                },
                            ),
                            seasonFolder?.resolve("poster"),
                            seasonFolder?.resolve("folder"),
                        ),
                        addedAt = "",
                    )
                }
            }
            val aired = nfo?.aired ?: parsed.aired?.toString()
            val title = nfo?.title ?: parsed.title ?: when {
                parsed.episode != null -> "Episode ${parsed.episode}"
                aired != null -> aired
                else -> stem
            }
            val order = listOfNotNull(parsed.episode?.toString()?.padStart(5, '0'), aired).joinToString(" ").ifEmpty {
                sortTitle(title)
            }
            items += LibraryItemRecord(
                id = itemId(libraryId, "file:${file.relative}"), libraryId = libraryId, kind = ItemKind.EPISODE,
                parentId =
                season?.id ?: show.id,
                path = file.relative, title = title, sortTitle = order,
                year = aired?.take(
                    4,
                )?.toIntOrNull(),
                season = seasonNumber,
                episode = nfo?.episode ?: parsed.episode, episodeEnd = parsed.episodeEnd, aired = aired,
                overview = nfo?.plot,
                durationMs = nfo?.runtimeMinutes?.let {
                    it * 60_000L
                },
                size = file.size, modifiedAt = file.modifiedAt, details = nfo?.details(),
                poster = art(root, path.resolveSibling("$stem-thumb"), path.resolveSibling(stem)),
                addedAt = "",
            )
        }
        return shows.values + seasons.values + items
    }
}

private val ART_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

/** The first of [bases] that exists with an image extension, relative to [root]; null when none does. */
private fun art(root: Path, vararg bases: Path?): String? {
    for (base in bases) {
        if (base == null) continue
        for (extension in ART_EXTENSIONS) {
            val candidate = base.resolveSibling("${base.fileName}.$extension")
            if (Files.isRegularFile(
                    candidate,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                )
            ) {
                return root.relativize(candidate).joinToString("/")
            }
        }
    }
    return null
}

private fun Nfo.details(tagged: Int? = null): ItemDetails = ItemDetails(
    tagline, genres, studio, contentRating, rating, cast, directors,
    tmdbId ?: tagged, nfo = true,
)

/** No `.nfo`, but maybe a TMDB id in the name. */
private fun tagged(id: Int?): ItemDetails? = id?.let { ItemDetails(tmdbId = it) }
