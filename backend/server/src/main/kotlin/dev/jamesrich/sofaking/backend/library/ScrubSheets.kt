package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.io.runFor
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.deleteOrphanLibraryScrubs
import dev.jamesrich.sofaking.backend.store.libraries
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.libraryScrubs
import dev.jamesrich.sofaking.backend.store.saveLibraryScrub
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ScrubSheets::class.java)

/** The scrub thumbnail pass's id on the Jobs page. */
internal const val SCRUB_JOB = "library-scrub"

/** One thumbnail every ten seconds of the file, 240 pixels wide, ten by ten to a sheet. */
internal const val SCRUB_INTERVAL_MS = 10_000L
internal const val SCRUB_TILE_WIDTH = 240
internal const val SCRUB_COLUMNS = 10
internal const val SCRUB_ROWS = 10

/** Files shorter than this get no thumbnails: a scrub through a few minutes needs none. */
internal const val SCRUB_MIN_MS = 300_000L

/**
 * One item's scrub thumbnails: [sheets] JPEG sheets of [columns] by [rows] tiles, each [tileWidth] by [tileHeight],
 * one every [intervalMs] from the start of the file, [frames] in all. [fileKey] names the file they were made from
 * ([fileKey] of its record). [sheets] is 0 when FFmpeg could make none, so the file is not tried again until it changes.
 */
data class ScrubRecord(
    val itemId: String,
    val fileKey: String,
    val intervalMs: Long,
    val tileWidth: Int,
    val tileHeight: Int,
    val columns: Int,
    val rows: Int,
    val sheets: Int,
    val frames: Int,
    val madeAt: String,
)

/** What a [SheetMaker] made: [sheets] sheets of tiles [tileWidth] by [tileHeight]. */
data class SheetSize(val tileWidth: Int, val tileHeight: Int, val sheets: Int)

/** Writes a file's thumbnail sheets into an empty folder as `0.jpg`, `1.jpg` and on; null when it can make none. */
fun interface SheetMaker {
    suspend fun make(file: Path, into: Path): SheetSize?
}

/**
 * Sheets through the server's FFmpeg, decoding only key frames: the frame nearest each [SCRUB_INTERVAL_MS], scaled to
 * [SCRUB_TILE_WIDTH] wide and tiled [SCRUB_COLUMNS] by [SCRUB_ROWS]; the last sheet holds what is left.
 */
class FfmpegSheetMaker(private val ffmpeg: String, private val timeout: Duration = 30.minutes) : SheetMaker {
    override suspend fun make(file: Path, into: Path): SheetSize? = withContext(Dispatchers.IO) {
        val exit = ProcessBuilder(
            ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-skip_frame", "nokey", "-i", file.toString(),
            "-map", "0:v:0", "-an", "-sn",
            "-vf", "fps=1000/$SCRUB_INTERVAL_MS,scale=$SCRUB_TILE_WIDTH:-2,tile=${SCRUB_COLUMNS}x$SCRUB_ROWS",
            "-q:v", "5", "-start_number", "0", "-y", into.resolve("%d.jpg").toString(),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).runFor(timeout)
            ?: throw IllegalStateException("ffmpeg ran past $timeout")
        // A file without video, or one FFmpeg cannot decode, fails here.
        if (exit != 0) return@withContext null
        val sheets = generateSequence(0) { it + 1 }.takeWhile { Files.isRegularFile(into.resolve("$it.jpg")) }.count()
        if (sheets == 0) return@withContext null
        val first = ImageIO.read(into.resolve("0.jpg").toFile()) ?: return@withContext null
        SheetSize(first.width / SCRUB_COLUMNS, first.height / SCRUB_ROWS, sheets)
    }
}

/**
 * Makes the sheets of thumbnails the library player shows over its seek bar while the viewer scrubs, the way Jellyfin
 * does with its trickplay images. After each scan of a library, its new and changed movies, episodes and videos of
 * [SCRUB_MIN_MS] or more get theirs, one file at a time, off the scan, under [directory]/{item}/. Sheets of items that
 * are gone are deleted with their rows at the end of each pass.
 */
class ScrubSheets(
    private val store: BackendStore,
    private val clock: Clock,
    private val jobs: Jobs,
    private val maker: SheetMaker,
    private val directory: Path,
) {
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    init {
        jobs.register(SCRUB_JOB, "Scrub thumbnails", runNow = { request(null) })
    }

    /** Queues a pass over the library [id], or over every library when null. */
    fun request(id: String?) {
        pending += id ?: ALL
        trigger.trySend(Unit)
    }

    /** Sheet [index] of [itemId], when it has been made. */
    fun sheet(itemId: String, index: Int): Path? {
        if (index < 0 || !ID.matches(itemId)) return null
        return directory.resolve(itemId).resolve("$index.jpg").takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }

    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("library-scrub")) {
        while (isActive) {
            trigger.receive()
            val wanted = pending.toList().also { pending.removeAll(it.toSet()) }
            try {
                val libraries = store.libraries().filter { ALL in wanted || it.id in wanted }
                for (library in libraries) run(library)
                if (libraries.isEmpty()) deleteOrphans()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("scrub thumbnails failed class={} message=\"{}\"", e.javaClass.simpleName, redactedMessage(e))
            }
        }
    }

    /** One pass over [library], recorded on the Jobs page: how many files got sheets, and how many could not. */
    suspend fun run(library: LibraryRecord): Pair<Int, Int> {
        var made = 0
        var failed = 0
        jobs.record(SCRUB_JOB) {
            val items = store.libraryItems(library.id)
                .filter { it.missingSince == null && it.path != null && it.kind in PLAYABLE && (it.durationMs ?: 0) >= SCRUB_MIN_MS }
            val known = store.libraryScrubs(items.map { it.id })
            val root = Path.of(library.path).normalize()
            for (item in items) {
                val key = fileKey(item)
                val had = known[item.id]
                // A deleted folder is made again, as a changed file is.
                if (had?.fileKey == key && (had.sheets == 0 || withContext(Dispatchers.IO) { Files.isDirectory(directory.resolve(item.id)) })) continue
                val file = root.resolve(item.path!!).normalize()
                if (!file.startsWith(root) || !withContext(Dispatchers.IO) { Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }) continue
                val size = make(item.id, file)
                if (size == null) failed++ else made++
                val frames = ((item.durationMs!! + SCRUB_INTERVAL_MS - 1) / SCRUB_INTERVAL_MS).toInt()
                store.saveLibraryScrub(
                    ScrubRecord(
                        item.id, key, SCRUB_INTERVAL_MS, size?.tileWidth ?: 0, size?.tileHeight ?: 0, SCRUB_COLUMNS, SCRUB_ROWS,
                        size?.sheets ?: 0, minOf(frames, (size?.sheets ?: 0) * SCRUB_COLUMNS * SCRUB_ROWS), clock.instant().toString(),
                    ),
                )
            }
            deleteOrphans()
            JobOutcome(true, "${library.name}: $made files" + if (failed > 0) ", $failed without video" else "")
        }
        return made to failed
    }

    /** Makes [itemId]'s sheets from [file] in a fresh folder, replacing the old ones only once the new ones are made. */
    @OptIn(ExperimentalPathApi::class)
    private suspend fun make(itemId: String, file: Path): SheetSize? {
        val target = directory.resolve(itemId)
        val work = directory.resolve("$itemId.new")
        withContext(Dispatchers.IO) {
            work.deleteRecursively()
            Files.createDirectories(work)
        }
        val size = try {
            maker.make(file, work)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("scrub sheets failed class={} message=\"{}\"", e.javaClass.simpleName, redactedMessage(e))
            null
        }
        withContext(Dispatchers.IO) {
            target.deleteRecursively()
            if (size != null) Files.move(work, target) else work.deleteRecursively()
        }
        return size
    }

    /** Drops the rows of items that are gone, such as those of a library removed, and every folder without a row. */
    @OptIn(ExperimentalPathApi::class)
    private suspend fun deleteOrphans() {
        val kept = store.deleteOrphanLibraryScrubs()
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(directory)) return@withContext
            Files.list(directory).use { entries -> entries.toList() }
                .filter { it.fileName.toString() !in kept }
                .forEach { it.deleteRecursively() }
        }
    }

    private companion object {
        const val ALL = "*"
        val PLAYABLE = setOf(ItemKind.MOVIE, ItemKind.EPISODE, ItemKind.VIDEO)

        /** Item ids as the store makes them, so a path segment never leaves [directory]. */
        val ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
