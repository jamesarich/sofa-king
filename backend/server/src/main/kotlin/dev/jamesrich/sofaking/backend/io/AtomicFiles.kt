package dev.jamesrich.sofaking.backend.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Replaces [path] with [bytes] through a temporary file beside it and an atomic rename, so a crash leaves the old
 * file or the new one. The temporary file is created by [Files.createTempFile], readable and writable by its owner
 * alone where the file system has POSIX permissions; when [path] already exists its permissions carry over. With
 * [sync] the bytes and the directory entry are forced to disk before the call returns; without it the rename is
 * still atomic but may be lost to a crash, which suits a cache that can fetch again.
 */
internal fun atomicWrite(path: Path, bytes: ByteArray, sync: Boolean = true) {
    val directory = path.toAbsolutePath().parent
    val temp = Files.createTempFile(directory, ".${path.fileName}.", ".tmp")
    try {
        runCatching { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(path)) }
        FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            if (sync) channel.force(true)
        }
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        // The rename is durable only once the directory entry is; not every platform can open a directory.
        if (sync) runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    } finally {
        Files.deleteIfExists(temp)
    }
}
