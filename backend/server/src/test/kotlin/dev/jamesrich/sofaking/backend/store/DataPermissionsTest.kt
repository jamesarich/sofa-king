package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.io.atomicWrite
import kotlinx.coroutines.runBlocking
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataPermissionsTest {
    private val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun mode(path: java.nio.file.Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test fun `opening the store limits the database and the sources file to the owner and leaves the directory`() =
        runBlocking {
            if (!posix) return@runBlocking
            val dir = Files.createTempDirectory("sofa-perm-")
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
                val sources = dir.resolve("sources.json")
                Files.writeString(sources, "{}")
                Files.setPosixFilePermissions(sources, PosixFilePermissions.fromString("rw-r--r--"))
                openStore(dir).use {
                    assertEquals("rwxr-xr-x", mode(dir))
                    assertEquals("rw-------", mode(dir.resolve("backend.sqlite")))
                    assertEquals("rw-------", mode(sources))
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test fun `rewriting the sources file keeps it owner-only`() {
        if (!posix) return
        val dir = Files.createTempDirectory("sofa-perm-rewrite-")
        try {
            val sources = dir.resolve("sources.json")
            atomicWrite(sources, "{}".toByteArray())
            assertEquals("rw-------", mode(sources))
            atomicWrite(sources, "{\"a\":1}".toByteArray())
            assertEquals("rw-------", mode(sources))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a directory that cannot be changed does not stop the server`() {
        if (!posix) return
        val missing = Files.createTempDirectory("sofa-perm-gone-").also { Files.delete(it) }
        restrictDataAccess(missing)
        assertTrue(Files.notExists(missing))
    }
}
