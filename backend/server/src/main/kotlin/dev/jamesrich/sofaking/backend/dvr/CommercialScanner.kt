package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.recording
import dev.jamesrich.sofaking.backend.store.recordings
import dev.jamesrich.sofaking.backend.store.saveRecording
import dev.jamesrich.sofaking.contract.RecordingBreak
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Finds the commercial breaks in finished recordings, one at a time, and keeps them on the recording unless it was
 * deleted or moved meanwhile. It shares the DVR's [mutex] for that last step.
 *
 * Three sources feed a recording's breaks. The markers in its source's playlists were read while it recorded and are
 * on the recording already; they are exact, so [learned] (the slates and ads the server learned) only looks at a
 * recording without any. [comskip], when there is one, runs on every recording. What the two guess is joined and
 * kept beside the markers (see [RecordingBreaks.combine]), and the EDL beside the file holds the lot. The scan is
 * `done` once any source answered, with no breaks when none found any, and `failed` only when every source that ran
 * failed: a missing Comskip alone does not fail a recording the other sources read.
 */
internal class CommercialScanner(
    private val store: BackendStore,
    private val files: RecordingFiles,
    private val comskip: Commercials?,
    private val learned: RecordingAds?,
    private val mutex: Mutex,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(Dvr::class.java)
    private val scans = Channel<String>(Channel.UNLIMITED)

    fun request(id: String) {
        scans.trySend(id)
    }

    /** Scans the recordings a restart interrupted, then each one [request] names, until canceled. */
    suspend fun run() {
        runCatchingCancellable {
            store.recordings().filter {
                it.commercials == Wire.CommercialScan.SCANNING
            }.forEach { scans.trySend(it.id) }
        }.onFailure { e ->
            log.warn("interrupted commercial scans not resumed class={}", e.javaClass.simpleName)
        }
        for (id in scans) {
            runCatchingCancellable {
                scan(id)
            }.onFailure { e ->
                log.warn("commercial scan failed id={} class={}", id, e.javaClass.simpleName)
            }
        }
    }

    internal suspend fun scan(id: String) {
        val recording =
            store.recording(id)?.takeIf { it.state == Wire.RecordingState.COMPLETED && it.path != null } ?: return
        val file = files.resolve(recording.path!!)
        val marked = recording.breaks
        val runs = detect(recording, file)
        val state = when {
            runs.isEmpty() && marked.isEmpty() -> null
            marked.isEmpty() && runs.all { it.second.isFailure } -> Wire.CommercialScan.FAILED
            else -> Wire.CommercialScan.DONE
        }
        val breaks = RecordingBreaks.combine(marked, runs.flatMap { it.second.getOrDefault(emptyList()) })
        runCatchingCancellable {
            withContext(Dispatchers.IO) { Commercials.writeEdl(file, breaks) }
        }.onFailure { e -> log.warn("EDL not written id={} class={}", id, e.javaClass.simpleName) }
        keep(recording, file, state, breaks)
        if (state != null) {
            log.info(
                "commercial scan id={} title=\"{}\" state={} breaks={} marked={}",
                id,
                recording.title,
                state,
                breaks.size,
                marked.size,
            )
        }
    }

    /** What each source that ran made of [file], by name: the learned source only without markers. */
    private suspend fun detect(
        recording: StoredRecording,
        file: Path,
    ): List<Pair<String, Result<List<RecordingBreak>>>> {
        val runs = buildList {
            learned?.takeIf { recording.breaks.isEmpty() && it.active }?.let {
                add("learned" to runCatchingCancellable { it.find(file, recording.title) })
            }
            comskip?.let { add("comskip" to runCatchingCancellable { it.detect(file) }) }
        }
        for ((source, result) in runs) {
            val e = result.exceptionOrNull() ?: continue
            log.warn(
                "commercial scan failed id={} title=\"{}\" source={} class={} message=\"{}\"",
                recording.id,
                recording.title,
                source,
                e.javaClass.simpleName,
                redactedMessage(e),
            )
        }
        return runs
    }

    /** Keeps the scan's outcome on the recording, unless it was deleted or moved meanwhile. */
    private suspend fun keep(recording: StoredRecording, file: Path, state: String?, breaks: List<RecordingBreak>) {
        mutex.withLock {
            val latest = store.recording(recording.id)?.takeIf { it.path == recording.path }
            if (latest == null) {
                // Deleted or moved during the scan, which then wrote its breaks beside a file that is gone.
                withContext(Dispatchers.IO) {
                    if (!Files.exists(file) &&
                        Files.deleteIfExists(Commercials.sidecar(file))
                    ) {
                        files.pruneEmpty(file.parent)
                    }
                }
            } else {
                store.saveRecording(latest.copy(commercials = state, breaks = breaks, updatedAt = clock.instant()))
            }
        }
    }
}
