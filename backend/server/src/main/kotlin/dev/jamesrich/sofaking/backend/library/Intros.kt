package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.io.runFor
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.deleteOrphanLibraryIntros
import dev.jamesrich.sofaking.backend.store.libraries
import dev.jamesrich.sofaking.backend.store.libraryIntros
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.saveLibraryCredits
import dev.jamesrich.sofaking.backend.store.saveLibraryFingerprint
import dev.jamesrich.sofaking.backend.store.saveLibraryIntros
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger(IntroDetector::class.java)

/** The intro pass's id on the Jobs page. */
internal const val INTRO_JOB = "library-intros"

/** How much of an episode is read for its intro: its first ten minutes, or its first 30% when that is shorter. */
private const val READ_MS = 600_000L
private const val READ_SHARE = 0.3

/** How much of an episode's end is read for its credits: its last tenth, at most five minutes. */
internal const val TAIL_SHARE = 0.1
private const val MAX_TAIL_MS = 300_000L

/**
 * Credits found ending this close to the end of the episode run to its end: the fingerprint's last points, a few
 * seconds' worth, never match, as Chromaprint needs audio after a point to fingerprint it.
 */
private const val CREDITS_END_SLACK_MS = 5_000L

/** How many other episodes each one is compared with. */
private const val PEERS = 4

/**
 * One episode's stored fingerprints, intro and credits. [fileKey] names the file as it was read ([fileKey] of its
 * record); [fingerprint] is null when it had no audio to read, and [intro] is null until one is found. [tail] is the
 * fingerprint of the episode's end from [tailStartMs], which is set once the end has been read (with no [tail] when
 * it had no audio); [credits] are from the start of the file, null until found.
 */
data class IntroRecord(
    val itemId: String,
    val fileKey: String,
    val fingerprint: IntArray?,
    val intro: IntroSpan?,
    val readAt: String,
    val tail: IntArray? = null,
    val tailStartMs: Long? = null,
    val credits: IntroSpan? = null,
)

/** The file as an item record knows it, so a replaced file is read again: its size and change time. */
internal fun fileKey(record: LibraryItemRecord): String = "${record.size}:${record.modifiedAt}"

/** What one pass over a library did, for the job's note. */
data class IntroResult(val read: Int, val failed: Int, val shows: Int, val intros: Int, val credits: Int = 0) {
    val note: String get() = "$read episodes read" + (if (failed > 0) ", $failed unreadable" else "") +
        ", $intros intros and $credits credits in $shows shows"
}

/**
 * Reads a fingerprint of [seconds] of a file's audio from [fromSeconds] in; null when the file has no audio FFmpeg
 * can read there.
 */
fun interface Fingerprinter {
    suspend fun fingerprint(file: Path, fromSeconds: Long, seconds: Long): IntArray?
}

/**
 * Chromaprint fingerprints through the `chromaprint` muxer of the server's FFmpeg, which writes the raw points to a
 * file in [work]: one little-endian 32-bit word per [IntroMatch.POINT_MS] of audio. Throws when FFmpeg cannot run.
 */
class FfmpegFingerprinter(
    private val ffmpeg: String,
    private val work: Path,
    private val timeout: Duration = 10.minutes,
) : Fingerprinter {
    override suspend fun fingerprint(file: Path, fromSeconds: Long, seconds: Long): IntArray? =
        withContext(Dispatchers.IO) {
            Files.createDirectories(work)
            val out = Files.createTempFile(work, "fingerprint-", ".raw")
            try {
                val exit = ProcessBuilder(
                    ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-ss", fromSeconds.toString(), "-t",
                    seconds.toString(), "-i", file.toString(),
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
 * Finds the intros and end credits of TV shows' episodes, the way Jellyfin's Intro Skipper does: the theme and titles
 * a show repeats sound the same in each episode, and so does the music under its credits. After each scan of a TV
 * shows library, its new and changed episodes are fingerprinted one at a time, off the scan, at the start and over
 * the last [tailShare] of the episode, and each show whose fingerprints changed is matched again: every episode
 * against the [PEERS] nearest others, the same season first (see [IntroMatch]). A show needs two episodes before
 * either has an intro or credits.
 */
class IntroDetector(
    private val store: BackendStore,
    private val clock: Clock,
    private val jobs: Jobs,
    private val fingerprinter: Fingerprinter,
    private val tailShare: Double = TAIL_SHARE,
) {
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    init {
        jobs.register(INTRO_JOB, "Intro and credits detection", runNow = { request(null) })
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
            runCatchingCancellable {
                val libraries = store.libraries().filter {
                    it.kind == LibraryKind.SHOWS && (ALL in wanted || it.id in wanted)
                }
                for (library in libraries) run(library)
            }.onFailure { e ->
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
        val shows = items.filter { it.kind == ItemKind.EPISODE && it.path != null }.groupBy { showOf(it, byId) }
        val pass = Pass(Path.of(library.path).normalize())
        for (episodes in shows.values) pass.show(episodes)
        // Episodes of a library removed while this pass ran.
        store.deleteOrphanLibraryIntros()
        return IntroResult(pass.read, pass.failed, shows.size, pass.intros, pass.credits)
    }

    /** One pass over the library at [root]: what it reads, what fails, and what it finds. */
    private inner class Pass(private val root: Path) {
        var read = 0
        var failed = 0
        var intros = 0
        var credits = 0

        /** Reads what [episodes] of one show still need, and matches them again when any of them changed. */
        suspend fun show(episodes: List<LibraryItemRecord>) {
            // One show's fingerprints in memory at a time.
            val known = HashMap(store.libraryIntros(episodes.map { it.id }))
            var changed = false
            for (episode in episodes) {
                val record = fingerprinted(episode, known[episode.id]) ?: continue
                store.saveLibraryFingerprint(record)
                known[episode.id] = record
                changed = true
            }
            if (changed) {
                matched(episodes, known)
            } else {
                intros += episodes.count { known[it.id]?.intro != null }
                credits += episodes.count { known[it.id]?.credits != null }
            }
        }

        /** [episode]'s fingerprints as read now, or null when [saved] still holds or there is no file to read. */
        private suspend fun fingerprinted(episode: LibraryItemRecord, saved: IntroRecord?): IntroRecord? {
            val key = fileKey(episode)
            val old = saved?.takeIf { it.fileKey == key }
            val tail = episode.durationMs?.let(::tailOf)
            val file = root.resolve(episode.path!!).normalize()
            // Read already, end and all; an episode whose length the scan does not know has no end to read.
            val unread = old == null || (old.tailStartMs == null && tail != null)
            if (!unread || !file.startsWith(root) || !isFile(file)) return null
            val record = old ?: opening(episode, file, key)
            return tail?.let { ending(record, file, it, counts = old != null) } ?: record
        }

        private suspend fun isFile(file: Path): Boolean =
            withContext(Dispatchers.IO) { Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }

        private suspend fun opening(episode: LibraryItemRecord, file: Path, key: String): IntroRecord {
            val seconds = (episode.durationMs?.let { minOf(READ_MS, (it * READ_SHARE).toLong()) } ?: READ_MS) / 1_000
            val points = fingerprinter.fingerprint(file, 0, seconds.coerceAtLeast(1))
            if (points == null) failed++ else read++
            return IntroRecord(episode.id, key, points, null, clock.instant().toString())
        }

        /** [record] with the end of its file read; a read counts when the opening was read on an earlier pass. */
        private suspend fun ending(record: IntroRecord, file: Path, tail: LongRange, counts: Boolean): IntroRecord {
            // A file whose start has no audio has none at its end either.
            val points = record.fingerprint?.let {
                fingerprinter.fingerprint(file, tail.first / 1_000, (tail.last - tail.first) / 1_000)
            }
            if (counts && points != null) read++
            return record.copy(tail = points, tailStartMs = tail.first, credits = null)
        }

        private suspend fun matched(episodes: List<LibraryItemRecord>, known: Map<String, IntroRecord>) {
            val foundIntros = match(episodes, known, { it.fingerprint }, IntroMatch.MAX_INTRO_MS)
            // Credits found in each episode's end, from the start of its file.
            val durations = episodes.associate { it.id to it.durationMs }
            val foundCredits = match(episodes, known, { it.tail }, IntroMatch.MAX_CREDITS_MS).mapValues { (id, span) ->
                span?.let { credits(it, known.getValue(id).tailStartMs!!, durations[id]) }
            }
            store.saveLibraryIntros(foundIntros)
            store.saveLibraryCredits(foundCredits)
            intros += foundIntros.values.count { it != null }
            credits += foundCredits.values.count { it != null }
        }
    }

    /**
     * [span] of an episode's end read from [tailStartMs], from the start of its file and
     * taken to its end when close to it.
     */
    private fun credits(span: IntroSpan, tailStartMs: Long, durationMs: Long?): IntroSpan {
        val end = tailStartMs + span.endMs
        return IntroSpan(
            tailStartMs + span.startMs,
            if (durationMs != null && end >= durationMs - CREDITS_END_SLACK_MS) {
                durationMs
            } else {
                end
            },
        )
    }

    /**
     * The end of an episode [durationMs] long that is read for its credits, in milliseconds: from a whole second, to
     * the second after its end, so its last moments are read.
     */
    private fun tailOf(durationMs: Long): LongRange? {
        val end = (durationMs + 999) / 1_000 * 1_000
        val start =
            (durationMs - minOf((durationMs * tailShare).toLong(), MAX_TAIL_MS)).coerceAtLeast(0) / 1_000 * 1_000
        return (start..end).takeIf { end - start >= IntroMatch.MIN_INTRO_MS }
    }

    /**
     * Each episode's span of the audio [points] picks, up to [maxMs] long, found against the nearest others of its
     * show; null where it shares none.
     */
    private fun match(
        episodes: List<LibraryItemRecord>,
        known: Map<String, IntroRecord>,
        points: (IntroRecord) -> IntArray?,
        maxMs: Long,
    ): Map<String, IntroSpan?> {
        val heard = episodes.filter { known[it.id]?.let(points) != null }
            .sortedWith(
                compareBy<LibraryItemRecord>({
                    it.season ?: 0
                }, { it.episode ?: Int.MAX_VALUE }, { it.sortTitle }),
            )
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
                    IntroMatch.shared(
                        points(known.getValue(heard[pair.first].id))!!,
                        points(known.getValue(heard[pair.second].id))!!,
                        maxMs = maxMs,
                    )
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
