package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.recording
import dev.jamesrich.sofaking.backend.store.recordings
import dev.jamesrich.sofaking.backend.store.saveRecording
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** A running recording's row and a scheduled one's, which a start or a finish may still change. */
private val LIVE = setOf(Wire.RecordingState.SCHEDULED, Wire.RecordingState.RECORDING)

/**
 * Runs the recordings the tick starts, each in its own job, and records how each ends. It shares the DVR's [mutex],
 * so a start, a finish and a delete each act on the latest row. [scan] queues a finished recording's commercial scan,
 * null without a detector, and [finished] runs after every finish.
 */
internal class RecordingRunner(
    private val store: BackendStore,
    private val recorder: Recorder,
    private val files: RecordingFiles,
    private val clock: Clock,
    private val zone: ZoneId,
    private val mutex: Mutex,
    private val scan: ((String) -> Unit)?,
    /** Where each start and finish is published; none without an event stream. */
    private val events: ServerEvents?,
    private val finished: () -> Unit,
) {
    private val running = ConcurrentHashMap<String, Job>()

    /** Why the server itself stopped a running recording early, by recording. */
    private val stopReasons = ConcurrentHashMap<String, String>()
    private var lastSpaceCull: Instant? = null

    fun isRunning(id: String): Boolean = running.containsKey(id)

    /** Stops [recording], keeping what it recorded, or finishes it when nothing runs it any more. */
    suspend fun stop(recording: StoredRecording) {
        running[recording.id]?.cancelAndJoin() ?: finish(recording, null)
    }

    /**
     * Keeps a running recording from filling the disk: below [floor] bytes free, [cull] runs (at most every
     * [Dvr.SPACE_CULL_EVERY]) and deletes finished recordings; below [Dvr.MIN_FREE_BYTES] with nothing left to delete,
     * the running recordings stop and keep what they wrote, with the reason [Dvr.DISK_FULL].
     */
    suspend fun guardSpace(now: Instant, floor: Long, cull: suspend () -> Unit) {
        if (running.isEmpty() || (files.free() ?: return) >= floor) return
        if (lastSpaceCull?.let { Duration.between(it, now) < Dvr.SPACE_CULL_EVERY } != true) {
            lastSpaceCull = now
            cull()
        }
        if ((files.free() ?: return) >= Dvr.MIN_FREE_BYTES) return
        for ((id, job) in running.entries.toList()) {
            stopReasons[id] = Dvr.DISK_FULL
            log.warn("recording stopped id={} reason={}", id, Dvr.DISK_FULL)
            job.cancel()
        }
    }

    /** Starts recording [recording] in [scope], unless a cancel or delete has landed since the tick chose it. */
    fun launch(recording: StoredRecording, scope: CoroutineScope) {
        val job = scope.launch(CoroutineName("recording:${recording.id}"), start = CoroutineStart.LAZY) {
            var current = recording
            var take: RecordedTake? = null
            try {
                current = mutex.withLock {
                    val latest = store.recording(current.id)?.takeIf { it.state in LIVE } ?: return@launch
                    val relative = latest.path?.takeIf { it.startsWith(RecordingLayout.IN_PROGRESS) }?.let(Path::of)
                        ?: RecordingLayout.inProgress(
                            RecordingLayout.path(latest.name, latest.startsAt, zone) { files.taken(it) },
                        )
                    latest.copy(
                        state = Wire.RecordingState.RECORDING,
                        reason = null,
                        path = relative.toString(),
                        updatedAt = clock.instant(),
                    )
                        .also {
                            store.saveRecording(it)
                            events.recordingChanged(it)
                        }
                }
                log.info(
                    "recording started id={} channel={} title=\"{}\"",
                    current.id,
                    current.channelId,
                    current.title,
                )
                take = recorder.record(current.channelId, files.resolve(current.path!!), current.recordUntil)
            } finally {
                // A shutdown cancels the whole scope: the row stays RECORDING in its in-progress file,
                // and the next start resumes it.
                if (scope.isActive) withContext(NonCancellable) { finish(current, take) }
            }
        }
        running[recording.id] = job
        job.invokeOnCompletion { running.remove(recording.id, job) }
        job.start()
    }

    /**
     * Moves the file into place and records the outcome; an empty or missing file fails the recording and is removed.
     */
    suspend fun finish(recording: StoredRecording, take: RecordedTake?) = mutex.withLock {
        val now = clock.instant()
        val stopReason = stopReasons.remove(recording.id)
        // A tick and a delete can both reach an orphan; the first to finish it wins.
        val latest = store.recording(recording.id)?.takeIf { it.state in LIVE } ?: return@withLock
        val partial = latest.path?.let { files.resolve(it) }
        val bytes = withContext(Dispatchers.IO) { partial?.takeIf(Files::exists)?.let(Files::size) ?: 0L }
        val reason = stopReason ?: take?.lastFailure
        if (partial == null || bytes == 0L) {
            fail(latest, partial, reason ?: "NO_MEDIA", now)
        } else {
            complete(latest, partial, bytes, reason, now)
        }
    }.also { finished() }

    private suspend fun fail(latest: StoredRecording, partial: Path?, reason: String, now: Instant) {
        withContext(Dispatchers.IO) { partial?.let(Files::deleteIfExists) }
        val failed =
            latest.copy(state = Wire.RecordingState.FAILED, reason = reason, path = null, bytes = 0, updatedAt = now)
        store.saveRecording(failed)
        events.recordingChanged(failed)
        log.warn("recording failed id={} title=\"{}\" reason={}", latest.id, latest.title, reason)
    }

    /** Keeps the [bytes] at [partial] at the recording's final path; [reason] is why it stopped, if it did. */
    private suspend fun complete(latest: StoredRecording, partial: Path, bytes: Long, reason: String?, now: Instant) {
        val final = RecordingLayout.path(latest.name, latest.startsAt, zone) { files.taken(it, except = latest.path) }
        files.place(partial, final)
        // A recording cut short by a stop or a source that never came back is still kept; the reason says why.
        val short = now < latest.recordUntil.minusSeconds(5)
        val commercials = if (scan != null) Wire.CommercialScan.SCANNING else null
        val completed = latest.copy(
            state = Wire.RecordingState.COMPLETED,
            reason = if (short) reason ?: "STOPPED" else null,
            path = final.toString(),
            bytes = bytes,
            commercials = commercials,
            updatedAt = now,
        )
        store.saveRecording(completed)
        events.recordingChanged(completed)
        scan?.invoke(latest.id)
        log.info("recording finished id={} title=\"{}\" bytes={} path=\"{}\"", latest.id, latest.title, bytes, final)
    }

    private companion object {
        val log = LoggerFactory.getLogger(Dvr::class.java)
    }
}
