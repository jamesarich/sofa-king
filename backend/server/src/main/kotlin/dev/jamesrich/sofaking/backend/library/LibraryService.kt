package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.deleteLibrary
import dev.jamesrich.sofaking.backend.store.deleteLibraryItems
import dev.jamesrich.sofaking.backend.store.insertLibrary
import dev.jamesrich.sofaking.backend.store.libraries
import dev.jamesrich.sofaking.backend.store.library
import dev.jamesrich.sofaking.backend.store.libraryItemCounts
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.markLibraryItemsMissing
import dev.jamesrich.sofaking.backend.store.recordLibraryScan
import dev.jamesrich.sofaking.backend.store.renameLibrary
import dev.jamesrich.sofaking.backend.store.saveLibraryItems
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(LibraryService::class.java)

/** The scan job's id on the Jobs page. */
internal const val LIBRARY_SCAN_JOB = "library-scan"

/** A folder a library may be added from: [root] is where the operator mounted media, [label] how the portal names it. */
data class MediaRoot(val label: String, val root: Path)

/** A folder whose name says what it holds, offered as a library to add with one click. */
data class LibrarySuggestion(val name: String, val kind: LibraryKind, val path: Path)

/** What a folder named so holds, by its name in lower case with separators as spaces. */
private val SUGGESTED_KINDS = mapOf(
    "movies" to LibraryKind.MOVIES, "movie" to LibraryKind.MOVIES, "films" to LibraryKind.MOVIES, "film" to LibraryKind.MOVIES,
    "tv" to LibraryKind.SHOWS, "tv shows" to LibraryKind.SHOWS, "shows" to LibraryKind.SHOWS, "series" to LibraryKind.SHOWS,
    "tv series" to LibraryKind.SHOWS, "television" to LibraryKind.SHOWS,
    "videos" to LibraryKind.VIDEOS, "home videos" to LibraryKind.VIDEOS, "music videos" to LibraryKind.VIDEOS,
)

/**
 * The media libraries: adding, renaming and removing them, and scanning their folders. A library's folder must sit
 * inside one of [roots]; the server only reads it. Scans run one at a time on the scan loop, every [every] for all
 * libraries and on request for one.
 */
class LibraryService(
    private val store: BackendStore,
    private val clock: Clock,
    /** The folders libraries may live under: the media directory and the DVR's recordings directory. */
    val roots: List<MediaRoot>,
    private val scanner: LibraryScanner,
    private val jobs: Jobs,
    private val every: Duration = Duration.ofHours(6),
    /** TMDB lookups after each scan, when `SOFA_TMDB_TOKEN` is set. */
    val metadata: LibraryMetadata? = null,
    /** The posters and backdrops `/artwork` serves. */
    val artwork: LibraryArtwork? = null,
) {
    /** Counts changes to what the libraries hold, so [catalog] rebuilds its view only after one. */
    private val revision = AtomicLong()

    /** What clients read: the home rows, the items, search and watch state. */
    val catalog = LibraryCatalog(store, clock) { revision.get() }

    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    /** Held while a scan writes items and while a library is removed; the TMDB pass runs without it. */
    private val scanLock = Mutex()
    /** One scan at a time, TMDB pass included. */
    private val scans = Mutex()

    init {
        jobs.register(LIBRARY_SCAN_JOB, "Library scan", every) { requestScan(null) }
    }

    suspend fun libraries(): List<LibraryRecord> = store.libraries()

    suspend fun library(id: String): LibraryRecord = store.library(id) ?: throw ApiFailure(404, "LIBRARY_NOT_FOUND", "No such library")

    /** Present items by library and kind. */
    suspend fun counts(): Map<String, Map<String, Int>> = store.libraryItemCounts()

    /**
     * The folders a new library can use: each root and its subfolders two levels down, skipping hidden and NAS
     * housekeeping folders and those another library already uses.
     */
    suspend fun folderChoices(): List<Path> {
        val used = store.libraries().mapTo(HashSet()) { it.path }
        return roots.flatMap { root -> folders(root.root, 2) }.filter { it.toString() !in used }
    }

    /**
     * The unused folders right under a root whose names say what they hold, such as `Movies` or `TV Shows`, named after
     * the folder; one under the recordings folder carries the root's label too, as `TV (Recordings)`.
     */
    suspend fun suggestions(): List<LibrarySuggestion> {
        val parents = roots.associateBy { it.root.normalize() }
        return folderChoices().mapNotNull { folder ->
            val root = parents[folder.parent] ?: return@mapNotNull null
            val name = folder.fileName.toString()
            val key = name.lowercase().replace(Regex("[_.\\-]+"), " ").replace(Regex("\\s+"), " ").trim()
            val kind = SUGGESTED_KINDS[key] ?: return@mapNotNull null
            LibrarySuggestion(if (root == roots.first()) name else "$name (${root.label})", kind, folder)
        }
    }

    private fun folders(root: Path, depth: Int): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val below = if (depth == 0) emptyList() else try {
            Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !skippedFolder(it.fileName.toString()) }.sorted().toList()
            }.flatMap { folders(it, depth - 1) }
        } catch (_: java.io.IOException) { emptyList() }
        return listOf(root.normalize()) + below
    }

    suspend fun create(name: String, kind: LibraryKind, path: String): LibraryRecord {
        val title = name.trim()
        if (title.isEmpty() || title.length > 100) throw ApiFailure(400, "INVALID_REQUEST", "A library name has 1 to 100 characters")
        val folder = allowed(path)
        if (store.libraries().any { it.path == folder.toString() }) throw ApiFailure(409, "LIBRARY_EXISTS", "Another library already uses that folder")
        val library = LibraryRecord(UUID.randomUUID().toString(), title, kind, folder.toString(), clock.instant().toString())
        store.insertLibrary(library)
        revision.incrementAndGet()
        requestScan(library.id)
        return library
    }

    suspend fun rename(id: String, name: String): LibraryRecord {
        val title = name.trim()
        if (title.isEmpty() || title.length > 100) throw ApiFailure(400, "INVALID_REQUEST", "A library name has 1 to 100 characters")
        if (!store.renameLibrary(id, title)) throw ApiFailure(404, "LIBRARY_NOT_FOUND", "No such library")
        revision.incrementAndGet()
        return library(id)
    }

    /** Forgets the library, its items and their watch state; its files stay where they are. */
    suspend fun delete(id: String) {
        scanLock.withLock { if (!store.deleteLibrary(id)) throw ApiFailure(404, "LIBRARY_NOT_FOUND", "No such library") }
        revision.incrementAndGet()
    }

    /** Queues a scan of [id], or of every library when null; requests before the loop runs coalesce. */
    fun requestScan(id: String?) {
        pending += id ?: ALL
        trigger.trySend(Unit)
    }

    /** Scans [id] now, on the caller, and records the outcome on the library and the Jobs page. */
    suspend fun scan(id: String): ScanResult = scans.withLock {
        val library = library(id)
        var result: ScanResult? = null
        jobs.record(LIBRARY_SCAN_JOB) {
            val now = clock.instant()
            val plan = scanLock.withLock {
                val plan = scanner.scan(library, Path.of(library.path), store.libraryItems(library.id), now)
                if (plan.save.isNotEmpty()) store.saveLibraryItems(plan.save)
                if (plan.missing.isNotEmpty()) store.markLibraryItemsMissing(plan.missing, now)
                if (plan.delete.isNotEmpty()) store.deleteLibraryItems(plan.delete)
                // Clients see the files now; the TMDB pass below can take minutes on a new library.
                if (plan.save.isNotEmpty() || plan.missing.isNotEmpty() || plan.delete.isNotEmpty()) revision.incrementAndGet()
                plan
            }
            val items = store.libraryItems(library.id)
            // Off the scan lock, so removing a library never waits for TMDB.
            val looked = if (plan.result.unavailable) null else metadata?.refresh(library, items)
            val note = listOfNotNull(plan.result.note, looked?.note).joinToString("; ")
            scanLock.withLock {
                // Removed during the TMDB pass, which then saved details for items that are gone.
                if (store.libraries().none { it.id == library.id }) store.deleteLibraryItems(items.map { it.id })
                else store.recordLibraryScan(library.id, now, !plan.result.unavailable, note)
            }
            revision.incrementAndGet()
            result = plan.result
            JobOutcome(!plan.result.unavailable && looked?.stopped == null, "${library.name}: $note")
        }
        result!!
    }

    fun start(scope: CoroutineScope): Job {
        scope.launch(CoroutineName("library-scan-timer")) {
            while (isActive) { requestScan(null); delay(every.toMillis()) }
        }
        return scope.launch(CoroutineName("library-scan")) {
            while (isActive) {
                trigger.receive()
                val wanted = pending.toList().also { pending.removeAll(it.toSet()) }
                val ids = try { if (ALL in wanted) store.libraries().map { it.id } else wanted } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    log.warn("library scan failed class={}", e.javaClass.simpleName)
                    emptyList()
                }
                for (id in ids) {
                    try { scan(id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        log.warn("library scan failed library={} class={} message=\"{}\"", id, e.javaClass.simpleName, redactedMessage(e))
                    }
                }
            }
        }
    }

    /** [path] as a normalized absolute folder inside one of [roots], or a 400 naming what is wrong. */
    private fun allowed(path: String): Path {
        val candidate = try { Path.of(path.trim()).toAbsolutePath().normalize() } catch (_: Exception) {
            throw ApiFailure(400, "INVALID_REQUEST", "Not a folder path")
        }
        val root = roots.firstOrNull { candidate.startsWith(it.root.toAbsolutePath().normalize()) }
            ?: throw ApiFailure(400, "INVALID_REQUEST", "A library folder must be inside ${roots.joinToString(" or ") { it.root.toString() }}")
        if (!Files.isDirectory(candidate)) throw ApiFailure(400, "INVALID_REQUEST", "No folder at that path")
        // A link inside the root must not lead the scan outside it.
        val real = candidate.toRealPath()
        if (!real.startsWith(root.root.toRealPath())) throw ApiFailure(400, "INVALID_REQUEST", "That folder links outside ${root.root}")
        return candidate
    }

    private companion object {
        const val ALL = "*"
    }
}
