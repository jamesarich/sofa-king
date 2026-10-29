package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.store.recording
import dev.jamesrich.sofaking.backend.store.recordings
import dev.jamesrich.sofaking.contract.RecordingStorage
import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isPresent
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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

    /** Makes the empty file at [relative], so [taken] sees it before the recorder writes its first bytes. */
    internal suspend fun claim(relative: Path) = withContext(Dispatchers.IO) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        if (!Files.exists(file)) Files.createFile(file)
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
