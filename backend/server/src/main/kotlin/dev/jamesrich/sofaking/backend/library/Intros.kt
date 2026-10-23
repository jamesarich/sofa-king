package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.io.runFor
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.deleteOrphanLibraryIntros
import dev.jamesrich.sofaking.backend.store.libraries
import dev.jamesrich.sofaking.backend.store.libraryIntros
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.saveLibraryFingerprint
import dev.jamesrich.sofaking.backend.store.saveLibraryIntros
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(IntroDetector::class.java)

/** The intro pass's id on the Jobs page. */
internal const val INTRO_JOB = "library-intros"

/** How much of an episode is read for its intro: its first ten minutes, or its first 30% when that is shorter. */
private const val READ_MS = 600_000L
private const val READ_SHARE = 0.3

/** How many other episodes each one is compared with. */
private const val PEERS = 4

/**
 * One episode's stored fingerprint and intro. [fileKey] names the file as it was read ([fileKey] of its record);
 * [fingerprint] is null when it had no audio to read, and [intro] is null until one is found.
 */
data class IntroRecord(val itemId: String, val fileKey: String, val fingerprint: IntArray?, val intro: IntroSpan?, val readAt: String)

/** The file as an item record knows it, so a replaced file is read again: its size and change time. */
internal fun fileKey(record: LibraryItemRecord): String = "${record.size}:${record.modifiedAt}"

/** What one pass over a library did, for the job's note. */
data class IntroResult(val read: Int, val failed: Int, val shows: Int, val intros: Int) {
    val note: String get() = "$read episodes read" + (if (failed > 0) ", $failed unreadable" else "") + ", $intros intros in $shows shows"
}

/** Reads a fingerprint of the first [seconds] of a file's audio; null when the file has no audio FFmpeg can read. */
fun interface Fingerprinter {
    suspend fun fingerprint(file: Path, seconds: Long): IntArray?
}

/**
 * Chromaprint fingerprints through the `chromaprint` muxer of the server's FFmpeg, which writes the raw points to a
 * file in [work]: one little-endian 32-bit word per [IntroMatch.POINT_MS] of audio. Throws when FFmpeg cannot run.
 */
class FfmpegFingerprinter(private val ffmpeg: String, private val work: Path, private val timeout: Duration = 10.minutes) : Fingerprinter {
    override suspend fun fingerprint(file: Path, seconds: Long): IntArray? = withContext(Dispatchers.IO) {
        Files.createDirectories(work)
        val out = Files.createTempFile(work, "fingerprint-", ".raw")
        try {
            val exit = ProcessBuilder(
                ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-t", seconds.toString(), "-i", file.toString(),
                "-map", "0:a:0", "-ac", "1", "-f", "chromaprint", "-fp_format", "raw", "-y", out.toString(),
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).runFor(timeout)
                ?: throw IllegalStateException("ffmpeg ran past $timeout")
            // A file without an audio stream, or one FFmpeg cannot decode, fails here.
            if (exit != 0) return@withContext null
            val words = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            IntArray(words.remaining()).also(words::get).takeIf { it.isNotEmpty() }
        } finally {
            withContext(NonCancellable) { Files.deleteIfExists(out) }
        }
    }
}

/**
 * Finds the intros of TV shows' episodes, the way Jellyfin's Intro Skipper does: the theme and titles a show repeats
 * sound the same in each episode. After each scan of a TV shows library, its new and changed episodes are
 * fingerprinted one at a time, off the scan, and each show whose fingerprints changed is matched again: every episode
 * against the [PEERS] nearest others, the same season first (see [IntroMatch]). A show needs two episodes before
 * either has an intro.
 */
class IntroDetector(
    private val store: BackendStore,
    private val clock: Clock,
    private val jobs: Jobs,
    private val fingerprinter: Fingerprinter,
) {
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    init {
        jobs.register(INTRO_JOB, "Intro detection", runNow = { request(null) })
    }

    /** Queues a pass over the library [id], or over every TV shows library when null. */
    fun request(id: String?) {
        pending += id ?: ALL
        trigger.trySend(Unit)
    }

    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("library-intros")) {
        while (isActive) {
            trigger.receive()
            val wanted = pending.toList().also { pending.removeAll(it.toSet()) }
            try {
                val libraries = store.libraries().filter { it.kind == LibraryKind.SHOWS && (ALL in wanted || it.id in wanted) }
                for (library in libraries) run(library)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("intro detection failed class={} message=\"{}\"", e.javaClass.simpleName, redactedMessage(e))
            }
        }
    }

    /** One pass over [library], recorded on the Jobs page. */
    suspend fun run(library: LibraryRecord): IntroResult? {
        if (library.kind != LibraryKind.SHOWS) return null
        var result: IntroResult? = null
        jobs.record(INTRO_JOB) {
            result = detect(library)
            JobOutcome(true, "${library.name}: ${result!!.note}")
        }
        return result
    }

    private suspend fun detect(library: LibraryRecord): IntroResult {
        val items = store.libraryItems(library.id).filter { it.missingSince == null }
        val byId = items.associateBy { it.id }
        val root = Path.of(library.path).normalize()
        val shows = items.filter { it.kind == ItemKind.EPISODE && it.path != null }.groupBy { showOf(it, byId) }
        var read = 0
        var failed = 0
        var intros = 0
        for ((_, episodes) in shows) {
            // One show's fingerprints in memory at a time.
            val known = HashMap(store.libraryIntros(episodes.map { it.id }))
            var changed = false
            for (episode in episodes) {
                val key = fileKey(episode)
                if (known[episode.id]?.fileKey == key) continue
                val file = root.resolve(episode.path!!).normalize()
                if (!file.startsWith(root) || !withContext(Dispatchers.IO) { Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }) continue
                val seconds = (episode.durationMs?.let { minOf(READ_MS, (it * READ_SHARE).toLong()) } ?: READ_MS) / 1_000
                val points = fingerprinter.fingerprint(file, seconds.coerceAtLeast(1))
                if (points == null) failed++ else read++
                val record = IntroRecord(episode.id, key, points, null, clock.instant().toString())
                store.saveLibraryFingerprint(record)
                known[episode.id] = record
                changed = true
            }
            val found = if (changed) match(episodes, known).also { store.saveLibraryIntros(it) } else episodes.associate { it.id to known[it.id]?.intro }
            intros += found.values.count { it != null }
        }
        // Episodes of a library removed while this pass ran.
        store.deleteOrphanLibraryIntros()
        return IntroResult(read, failed, shows.size, intros)
    }

    /** Each fingerprinted episode's intro, found against the nearest others of its show. */
    private fun match(episodes: List<LibraryItemRecord>, known: Map<String, IntroRecord>): Map<String, IntroSpan?> {
        val heard = episodes.filter { known[it.id]?.fingerprint != null }
            .sortedWith(compareBy<LibraryItemRecord>({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE }, { it.sortTitle }))
        val shared = HashMap<Pair<Int, Int>, SharedSpan?>()
        return episodes.associate { episode ->
            val index = heard.indexOf(episode)
            if (index < 0) return@associate episode.id to null
            val peers = heard.indices.filter { it != index }
                .sortedWith(compareBy({ heard[it].season != episode.season }, { kotlin.math.abs(it - index) }))
                .take(PEERS)
            val spans = peers.mapNotNull { peer ->
                // Each pair is compared once, from the episode earlier in play order.
                val pair = minOf(index, peer) to maxOf(index, peer)
                val match = shared.getOrPut(pair) {
                    IntroMatch.shared(known.getValue(heard[pair.first].id).fingerprint!!, known.getValue(heard[pair.second].id).fingerprint!!)
                }
                if (index == pair.first) match?.first else match?.second
            }
            // With three or more others to agree, a stretch only one shares (a recap, a two-parter) is not the intro.
            episode.id to IntroMatch.intro(spans, minAgreeing = if (peers.size >= 3) 2 else 1)
        }
    }

    private fun showOf(episode: LibraryItemRecord, byId: Map<String, LibraryItemRecord>): String {
        var item = episode
        while (item.kind != ItemKind.SHOW) item = item.parentId?.let(byId::get) ?: return item.parentId ?: item.id
        return item.id
    }

    private companion object {
        const val ALL = "*"
    }
}
