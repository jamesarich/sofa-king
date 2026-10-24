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
 * The recordings folder [root] on disk: where a recording's file may go, the files a deletion removes, and the space
 * left, which [freeSpace] reads from the folder's disk.
 */
class RecordingFiles internal constructor(val root: Path, private val freeSpace: (Path) -> Long?) {
    /** Whether the server can write the folder, made when it is missing; false means every recording fails. */
    fun writable(): Boolean = runCatching {
        Files.createDirectories(root)
        Files.isWritable(root)
    }.getOrDefault(false)

    /** The free space on the folder's disk, made first when it is missing; null when it cannot be read. */
    internal suspend fun free(): Long? = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        freeSpace(root)
    }

    internal fun resolve(relative: String): Path = root.resolve(relative)

    /**
     * True when a file on disk takes [relative], in place or in progress; [except] is the
     * caller's own in-progress path.
     */
    internal fun taken(relative: Path, except: String? = null): Boolean {
        val running = RecordingLayout.inProgress(relative)
        return Files.exists(root.resolve(relative)) ||
            (running.toString() != except && Files.exists(root.resolve(running)))
    }

    /** Moves a finished recording's [partial] file to [final], under the folder, and prunes what it leaves empty. */
    internal suspend fun place(partial: Path, final: Path) = withContext(Dispatchers.IO) {
        Files.createDirectories(root.resolve(final).parent)
        Files.move(partial, root.resolve(final), StandardCopyOption.ATOMIC_MOVE)
        pruneEmpty(partial.parent)
    }

    /** Deletes the recording at [relative] and its commercials file, when it is under the folder. */
    internal fun remove(relative: String) {
        val file = root.resolve(relative).normalize()
        if (!file.startsWith(root.normalize())) return
        Files.deleteIfExists(file)
        Files.deleteIfExists(Commercials.sidecar(file))
        pruneEmpty(file.parent)
    }

    /** Removes [directory] and its parents while they are empty, stopping at the folder itself. */
    internal fun pruneEmpty(directory: Path?) {
        var current = directory
        while (current != null && current.normalize() != root.normalize() && current.startsWith(root)) {
            val empty = Files.isDirectory(current) && Files.list(current).use { !it.findAny().isPresent }
            if (!empty) return
            Files.delete(current)
            current = current.parent
        }
    }

    /** The space [all] recordings use, and what the folder's disk has free and in all. */
    internal fun storage(all: List<StoredRecording>): RecordingStorage {
        val used = all.filter {
            it.state == Wire.RecordingState.COMPLETED || it.state == Wire.RecordingState.RECORDING
        }.sumOf { it.bytes }
        val store =
            runCatching {
                Files.createDirectories(root)
                Files.getFileStore(root)
            }.getOrNull()
                ?: return RecordingStorage(usedBytes = used)
        return RecordingStorage(store.usableSpace, store.totalSpace, used)
    }
}
