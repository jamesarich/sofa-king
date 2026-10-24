package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.GuideService
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.catalog.redactedMessage
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.library.PlayableFile
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.Recordings
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.deleteRecording
import dev.jamesrich.sofaking.backend.store.deleteRecordingRule
import dev.jamesrich.sofaking.backend.store.guides
import dev.jamesrich.sofaking.backend.store.lineup
import dev.jamesrich.sofaking.backend.store.programs
import dev.jamesrich.sofaking.backend.store.recording
import dev.jamesrich.sofaking.backend.store.recordingRule
import dev.jamesrich.sofaking.backend.store.recordingRules
import dev.jamesrich.sofaking.backend.store.recordings
import dev.jamesrich.sofaking.backend.store.saveRecording
import dev.jamesrich.sofaking.backend.store.saveRecordingRule
import dev.jamesrich.sofaking.backend.store.stored
import dev.jamesrich.sofaking.contract.CreateRecordingRule
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingList
import dev.jamesrich.sofaking.contract.RecordingRule
import dev.jamesrich.sofaking.contract.RecordingStorage
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.UpdateRecording
import dev.jamesrich.sofaking.contract.UpdateRecordingRule
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.isPresent
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the commercial breaks in finished recordings with [detector], one at a time, and keeps them on the recording
 * unless it was deleted or moved meanwhile. It shares the DVR's [mutex] for that last step.
 */
internal class CommercialScanner(
    private val store: BackendStore,
    private val files: RecordingFiles,
    private val detector: Commercials,
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

    private suspend fun scan(id: String) {
        val recording =
            store.recording(id)?.takeIf { it.state == Wire.RecordingState.COMPLETED && it.path != null } ?: return
        val outcome = runCatchingCancellable {
            Wire.CommercialScan.DONE to detector.detect(files.resolve(recording.path!!))
        }.getOrElse { e ->
            log.warn(
                "commercial scan failed id={} title=\"{}\" class={} message=\"{}\"",
                id,
                recording.title,
                e.javaClass.simpleName,
                redactedMessage(e),
            )
            Wire.CommercialScan.FAILED to emptyList()
        }
        mutex.withLock {
            val latest = store.recording(id)?.takeIf { it.path == recording.path }
            if (latest == null) {
                // Deleted or moved during the scan, which then wrote its breaks beside a file that is gone.
                val file = files.resolve(recording.path!!)
                withContext(Dispatchers.IO) {
                    if (!Files.exists(file) &&
                        Files.deleteIfExists(Commercials.sidecar(file))
                    ) {
                        files.pruneEmpty(file.parent)
                    }
                }
            } else {
                store.saveRecording(
                    latest.copy(commercials = outcome.first, breaks = outcome.second, updatedAt = clock.instant()),
                )
            }
        }
        if (outcome.first == Wire.CommercialScan.DONE) {
            log.info("commercial scan id={} title=\"{}\" breaks={}", id, recording.title, outcome.second.size)
        }
    }
}
