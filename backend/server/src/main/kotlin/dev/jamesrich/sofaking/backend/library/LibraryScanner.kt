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

private val MISSING_GRACE: Duration = Duration.ofDays(7)

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
    private val missingGrace: Duration = MISSING_GRACE,
) {
    suspend fun scan(library: LibraryRecord, root: Path, existing: List<LibraryItemRecord>, now: Instant): ScanPlan {
        val files = withContext(Dispatchers.IO) { listFiles(root) }
        val present = existing.count { it.missingSince == null && it.path != null }
        // An empty or absent folder that held files is an unmounted disk, not a library whose every file was deleted.
        if (files == null || (files.isEmpty() && present > 0)) {
            return ScanPlan(emptyList(), emptyList(), emptyList(), ScanResult(0, 0, 0, 0, 0, unavailable = true))
        }
        val built = withContext(Dispatchers.IO) { items(library, root, files) }
        val byId = existing.associateBy { it.id }
        val probing = Probing(byId, root, now)
        val saved = withNewestAdds(probing.all(built))
        val seen = saved.mapTo(HashSet()) { it.id }
        val gone = existing.filter { it.id !in seen }
        val missing = gone.filter { it.missingSince == null }.map { it.id }
        val delete = gone.filter { old ->
            old.missingSince?.let { Duration.between(Instant.parse(it), now) >= missingGrace } == true
        }.map { it.id }
        val result = ScanResult(
            probing.added.get(),
            probing.updated.get(),
            missing.count { byId[it]?.path != null },
            delete.size,
            built.count { it.path != null },
        )
        // An unchanged row is not rewritten, so a rescan of a big library is not one long write
        // on the database's only thread.
        return ScanPlan(saved.filter { it != byId[it.id] }, missing, delete, result)
    }

    /** The items [files] make in [library]: its movies, shows or videos, and their extras. */
    private fun items(library: LibraryRecord, root: Path, files: List<FoundFile>): List<LibraryItemRecord> {
        val (extraFiles, mainFiles) = files.partition { it.extra != null }
        val main = when (library.kind) {
            LibraryKind.MOVIES -> mainFiles.map { movieItem(library.id, root, it) }
            LibraryKind.SHOWS -> ShowItems(library.id, root).apply { mainFiles.forEach(::add) }.items()
            LibraryKind.VIDEOS -> mainFiles.map { videoItem(library.id, root, it) }
        }
        return main + extras(library, main, extraFiles)
    }

    /**
     * Gives each item what [probe] reads of its file, which only a new or changed file is probed for again, and
     * counts the files [added] and [updated] since the last scan ([byId]).
     */
    private inner class Probing(
        private val byId: Map<String, LibraryItemRecord>,
        private val root: Path,
        private val now: Instant,
    ) {
        val added = AtomicInteger()
        val updated = AtomicInteger()
        private val semaphore = Semaphore(probeParallelism)

        suspend fun all(items: List<LibraryItemRecord>): List<LibraryItemRecord> =
            coroutineScope { items.map { async { probed(it) } }.awaitAll() }

        private suspend fun probed(item: LibraryItemRecord): LibraryItemRecord {
            val old = byId[item.id]
            val unchanged =
                old != null && old.size == item.size && old.modifiedAt == item.modifiedAt && old.media != null
            val media = when {
                item.path == null -> null
                unchanged -> old.media
                else -> semaphore.withPermit { probe(root.resolve(item.path)) }
            }
            if (item.path != null && old == null) {
                added.incrementAndGet()
            } else if (item.path != null && !unchanged) {
                updated.incrementAndGet()
            }
            return item.copy(
                media = media,
                durationMs = media?.durationMs ?: item.durationMs,
                addedAt = old?.addedAt ?: item.addedAt.ifEmpty { now.toString() },
            )
        }
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
            val kind = file.extra ?: return@mapNotNull null
            val parent = ownerOf(file, library, movies, shows) ?: return@mapNotNull null
            val stem = file.relative.substringAfterLast('/').substringBeforeLast('.')
            val title = if (file.ownerStem != null) kind.label else titleWithYear(stem)
            LibraryItemRecord(
                id = itemId(library.id, "file:${file.relative}"),
                libraryId = library.id, kind = ItemKind.EXTRA, parentId = parent,
                path = file.relative, title = title, sortTitle = "${kind.ordinal} ${sortTitle(title)}",
                size = file.size, modifiedAt = file.modifiedAt, details = ItemDetails(extra = kind.wire), addedAt = "",
            )
        }
    }

    /** The id of the movie ([movies] by folder) or show ([shows]) that [file] is an extra of; null when none is. */
    private fun ownerOf(
        file: FoundFile,
        library: LibraryRecord,
        movies: Map<String, List<LibraryItemRecord>>,
        shows: Set<String>,
    ): String? {
        val owner = file.owner ?: return null
        if (library.kind == LibraryKind.MOVIES) {
            val here = movies[owner].orEmpty()
            val named = file.ownerStem?.let { stem ->
                here.firstOrNull { it.path!!.substringAfterLast('/').substringBeforeLast('.') == stem }
            }
            return (named ?: here.firstOrNull()?.takeIf { owner.isNotEmpty() || here.size == 1 })?.id
        }
        return owner.takeIf { it.isNotEmpty() }
            ?.let { itemId(library.id, "show:${it.substringBefore('/')}") }
            ?.takeIf { it in shows }
    }
}

/**
 * A show or season takes the newest add time of what it holds, so a new episode brings its show up in Recently
 * added.
 */
private fun withNewestAdds(items: List<LibraryItemRecord>): List<LibraryItemRecord> {
    val parents = items.associate { it.id to it.parentId }
    val newest = HashMap<String, String>()
    // An extra found later does not bring its movie back up.
    for (item in items.filter { it.kind != ItemKind.EXTRA }) {
        var parent = item.parentId
        while (parent != null) {
            if ((newest[parent] ?: "") < item.addedAt) newest[parent] = item.addedAt
            parent = parents[parent]
        }
    }
    return items.map { item -> newest[item.id]?.let { item.copy(addedAt = it) } ?: item }
}
