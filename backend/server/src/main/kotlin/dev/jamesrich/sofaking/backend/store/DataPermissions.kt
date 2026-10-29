package dev.jamesrich.sofaking.backend.store

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private val log = LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.store.DataPermissions")

private val OWNER_FILE = PosixFilePermissions.fromString("rw-------")

/** The files in the data directory that hold tokens, credentials or watch history. */
private val PRIVATE_FILES =
    listOf("backend.sqlite", "backend.sqlite-wal", "backend.sqlite-shm", "sources.json", "secrets.json")

/**
 * Limits the private files in [directory] to read and write for their owner. The directory itself is left alone, since
 * a media server on the host may read recordings kept in it. An atomic rewrite carries a file's mode over, so a
 * rewritten `sources.json` stays owner-only. On a file system without POSIX permissions, or one that refuses the
 * change, the server starts anyway and logs one warning.
 */
internal fun restrictDataAccess(directory: Path) {
    if (!directory.fileSystem.supportedFileAttributeViews().contains("posix")) return
    val refused = PRIVATE_FILES.map(directory::resolve).filter { Files.isRegularFile(it) }.filterNot {
        runCatching { Files.setPosixFilePermissions(it, OWNER_FILE) }.isSuccess
    }
    if (refused.isNotEmpty()) {
        log.warn(
            "Could not limit {} to the server's user; other users of the host may read them",
            refused.joinToString { it.fileName.toString() },
        )
    }
}
