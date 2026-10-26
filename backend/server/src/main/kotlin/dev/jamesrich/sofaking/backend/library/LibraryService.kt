package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.JobProgress
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.runCatchingCancellable
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
import io.ktor.http.HttpStatusCode
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
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val log = LoggerFactory.getLogger(LibraryService::class.java)

/** The scan job's id on the Jobs page. */
internal const val LIBRARY_SCAN_JOB = "library-scan"

private val SCAN_EVERY: Duration = Duration.ofHours(6)
private const val LIBRARY_NAME_LIMIT = 100

/**
 * A folder a library may be added from: [root] is where the operator mounted media, [label] how the portal names it.
 */
data class MediaRoot(val label: String, val root: Path)

/**
 * The media libraries: adding, renaming and removing them, and scanning their folders. A library's folder must sit
 * inside one of [roots]; the server only reads it. Scans run one at a time on the scan loop, every [every] for all
 * libraries and on request for one. A scan of a TV shows library then asks [intros] to read its new episodes, and every
 * scan asks [scrub] to make thumbnails for its new files.
 */
class LibraryService(
    private val store: BackendStore,
    private val clock: Clock,
    /** The folders libraries may live under: the media directory and the DVR's recordings directory. */
    val roots: List<MediaRoot>,
    private val scanner: LibraryScanner,
    private val jobs: Jobs,
    private val every: Duration = SCAN_EVERY,
    /** TMDB lookups after each scan, when `SOFA_TMDB_TOKEN` is set. */
    val metadata: LibraryMetadata? = null,
    /** MusicBrainz lookups after each scan of a Music library, unless `SOFA_MUSICBRAINZ` turns them off. */
    private val musicMetadata: MusicMetadata? = null,
    /** The posters and backdrops `/artwork` serves. */
    val artwork: LibraryArtwork? = null,
    /** Finds TV episodes' intros after each scan of a TV shows library. */
    private val intros: IntroDetector? = null,
    /** Makes scrub thumbnails after each scan; `/artwork` serves them. */
    val scrub: ScrubSheets? = null,
    /** How Sonarr, Radarr and the like name the media folders, where they see them under another path. */
    private val pathMap: List<PathMapping> = emptyList(),
    /** Where a scan that adds files publishes `library.added`. */
    private val events: ServerEvents? = null,
) {
    /** Counts changes to what the libraries hold, so [catalog] rebuilds its view only after one. */
    private val revision = AtomicLong()

    /** What clients read: the home rows, the items, search and watch state. */
    val catalog = LibraryCatalog(store, clock) { revision.get() }

    /** The folders a library may be added from. */
    val folders = LibraryFolders(store, roots)

    /** The movies and shows TMDB could not match, and the matches chosen by hand; null without a TMDB token. */
    val matches = metadata?.let { LibraryMatches(store, clock, it) { id -> requestScan(id) } }

    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /**
     * Held while a scan writes items and while a library is removed; listing, probing and the TMDB pass run without it.
     */
    private val scanLock = Mutex()

    /** One scan at a time, TMDB pass included. */
    private val scans = Mutex()

    /** Held from the check that a folder is free to the insert that takes it. */
    private val creating = Mutex()

    init {
        jobs.register(LIBRARY_SCAN_JOB, "Library scan", every) { requestScan(null) }
    }

    suspend fun libraries(): List<LibraryRecord> = store.libraries()

    suspend fun library(id: String): LibraryRecord =
        store.library(id) ?: throw ApiFailure(HttpStatusCode.NotFound, "LIBRARY_NOT_FOUND", "No such library")

    /** Present items by library and kind. */
    suspend fun counts(): Map<String, Map<String, Int>> = store.libraryItemCounts()

    suspend fun create(name: String, kind: LibraryKind, path: String): LibraryRecord {
        val title = titleOf(name)
        val folder = folders.allowed(path)
        val library =
            LibraryRecord(UUID.randomUUID().toString(), title, kind, folder.toString(), clock.instant().toString())
        creating.withLock {
            if (store.libraries().any { it.path == folder.toString() }) {
                throw ApiFailure(HttpStatusCode.Conflict, "LIBRARY_EXISTS", "Another library already uses that folder")
            }
            store.insertLibrary(library)
        }
        revision.incrementAndGet()
        requestScan(library.id)
        return library
    }

    suspend fun rename(id: String, name: String): LibraryRecord {
        if (!store.renameLibrary(id, titleOf(name))) {
            throw ApiFailure(HttpStatusCode.NotFound, "LIBRARY_NOT_FOUND", "No such library")
        }
        revision.incrementAndGet()
        return library(id)
    }

    private fun titleOf(name: String): String =
        name.trim().takeIf { it.isNotEmpty() && it.length <= LIBRARY_NAME_LIMIT }
            ?: throw ApiFailure(
                HttpStatusCode.BadRequest,
                "INVALID_REQUEST",
                "A library name has 1 to $LIBRARY_NAME_LIMIT characters",
            )

    /** Forgets the library, its items and their watch state; its files stay where they are. */
    suspend fun delete(id: String) {
        scanLock.withLock {
            if (!store.deleteLibrary(id)) {
                throw ApiFailure(HttpStatusCode.NotFound, "LIBRARY_NOT_FOUND", "No such library")
            }
        }
        revision.incrementAndGet()
        // Its scrub thumbnails go with the next pass, which finds their items gone.
        scrub?.request(null)
    }

    /** Queues a scan of [id], or of every library when null; requests before the loop runs coalesce. */
    fun requestScan(id: String?) {
        pending += id ?: ALL
        trigger.trySend(Unit)
    }

    /**
     * Queues a scan of the libraries a change at [path] concerns, as another program names the path, and returns them;
     * none when no library holds it. Requests for one library coalesce until its scan starts.
     */
    suspend fun requestScanAt(path: String): List<LibraryRecord> {
        val local = localPath(path, pathMap) ?: return emptyList()
        return librariesAt(local, store.libraries()).onEach { requestScan(it.id) }
    }

    /** Scans [id] now, on the caller, and records the outcome on the library and the Jobs page. */
    suspend fun scan(id: String): ScanResult = scans.withLock {
        val library = library(id)
        var result: ScanResult? = null
        jobs.record(LIBRARY_SCAN_JOB) {
            val now = clock.instant()
            // Listing and probing a new library takes minutes, so it runs off the lock too; only the writes hold it.
            jobs.progress(LIBRARY_SCAN_JOB, JobProgress(0, 0, library.name))
            val before = store.libraryItems(library.id)
            val plan = scanner.scan(library, Path.of(library.path), before, now)
            scanLock.withLock {
                // Removed since this scan began: it writes nothing.
                if (store.libraries().none { it.id == library.id }) {
                    throw ApiFailure(HttpStatusCode.NotFound, "LIBRARY_NOT_FOUND", "No such library")
                }
                if (plan.save.isNotEmpty()) store.saveLibraryItems(plan.save)
                if (plan.missing.isNotEmpty()) store.markLibraryItemsMissing(plan.missing, now)
                if (plan.delete.isNotEmpty()) store.deleteLibraryItems(plan.delete)
                // Clients see the files now; the TMDB pass below can take minutes on a new library.
                if (plan.save.isNotEmpty() || plan.missing.isNotEmpty() || plan.delete.isNotEmpty()) {
                    revision.incrementAndGet()
                }
            }
            val items = store.libraryItems(library.id)
            events.libraryAdded(library, plan.save, before.mapTo(HashSet()) { it.id }, items)
            // Off the scan lock, so removing a library never waits for TMDB.
            val looked = when {
                plan.result.unavailable -> null
                library.kind == LibraryKind.MUSIC -> musicMetadata?.refresh(library, items)
                else -> metadata?.refresh(library, items)
            }
            val note = listOfNotNull(plan.result.note, looked?.note).joinToString("; ")
            scanLock.withLock {
                // Removed during the TMDB pass, which then saved details for items that are gone.
                if (store.libraries().none { it.id == library.id }) {
                    store.deleteLibraryItems(items.map { it.id })
                } else {
                    store.recordLibraryScan(library.id, now, !plan.result.unavailable, note)
                }
            }
            revision.incrementAndGet()
            if (library.kind == LibraryKind.SHOWS && !plan.result.unavailable) intros?.request(library.id)
            if (!plan.result.unavailable) scrub?.request(library.id)
            result = plan.result
            JobOutcome(!plan.result.unavailable && looked?.stopped == null, "${library.name}: $note")
        }
        result!!
    }

    fun start(scope: CoroutineScope): Job {
        scope.launch(CoroutineName("library-scan-timer")) {
            while (isActive) {
                requestScan(null)
                delay(every.toMillis())
            }
        }
        return scope.launch(CoroutineName("library-scan")) {
            while (isActive) {
                trigger.receive()
                val wanted = pending.toList().also { pending.removeAll(it.toSet()) }
                val ids = runCatchingCancellable {
                    if (ALL in wanted) store.libraries().map { it.id } else wanted
                }.getOrElse { e ->
                    log.warn("library scan failed class={}", e.javaClass.simpleName)
                    emptyList()
                }
                for (id in ids) {
                    runCatchingCancellable {
                        scan(id)
                    }.onFailure { e ->
                        log.warn(
                            "library scan failed library={} class={} message=\"{}\"",
                            id,
                            e.javaClass.simpleName,
                            redactedMessage(e),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val ALL = "*"
    }
}
