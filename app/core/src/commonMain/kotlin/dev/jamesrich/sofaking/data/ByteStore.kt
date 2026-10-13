package dev.jamesrich.sofaking.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** One blob of app-private bytes: a file on devices with a filesystem, browser storage on the web. */
interface ByteStore {
    /** The stored bytes, or null when nothing is stored. */
    fun read(): ByteArray?

    /** Replaces what is stored; a failure leaves the previous bytes intact. */
    fun write(bytes: ByteArray)

    fun delete()
}

/** A [ByteStore] in one file, replaced atomically through a temporary sibling. */
class FileByteStore(private val path: Path) : ByteStore {
    override fun read(): ByteArray? {
        if (!SystemFileSystem.exists(path)) return null
        return SystemFileSystem.source(path).buffered().use { it.readByteArray() }
    }

    override fun write(bytes: ByteArray) {
        val parent = path.parent
        if (parent != null) SystemFileSystem.createDirectories(parent)
        val temp = Path(parent ?: Path("."), path.name + ".tmp")
        try {
            SystemFileSystem.sink(temp).buffered().use { it.write(bytes) }
            SystemFileSystem.atomicMove(temp, path)
        } catch (e: IOException) {
            SystemFileSystem.delete(temp, mustExist = false)
            throw e
        }
    }

    override fun delete() {
        SystemFileSystem.delete(path, mustExist = false)
    }
}

/** Where file and decoding work runs: the IO pool where the platform has one, the default pool on the web. */
internal expect val ioDispatcher: CoroutineDispatcher
