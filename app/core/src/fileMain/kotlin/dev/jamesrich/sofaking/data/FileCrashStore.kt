package dev.jamesrich.sofaking.data

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

private const val EXTENSION = ".json"

/**
 * Crashes as one file each under [directory], named by their key so that the listing sorts oldest first. Everything
 * is blocking and keeps to the file system, as a crash is written while the process dies.
 */
class FileCrashStore(private val directory: String) : CrashStore {
    private val root = Path(directory)

    override fun save(key: String, json: String) {
        SystemFileSystem.createDirectories(root)
        val temporary = Path(root, "$key.tmp")
        SystemFileSystem.sink(temporary).buffered().use { it.writeString(json) }
        SystemFileSystem.atomicMove(temporary, Path(root, key + EXTENSION))
    }

    override fun pending(): List<StoredCrash> {
        if (!SystemFileSystem.exists(root)) return emptyList()
        return SystemFileSystem.list(root)
            .filter { it.name.endsWith(EXTENSION) }
            .sortedBy { it.name }
            .mapNotNull { file ->
                runCatchingCancellable { SystemFileSystem.source(file).buffered().use { it.readString() } }.getOrNull()
                    ?.let { StoredCrash(file.name.removeSuffix(EXTENSION), it) }
            }
    }

    override fun remove(key: String) {
        SystemFileSystem.delete(Path(root, key + EXTENSION), mustExist = false)
    }
}
