package dev.jamesrich.sofaking.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/** The cache's DataStore in a real file: the path every platform with a file system uses. */
class LineupCacheFileTest {
    private val dir = Path(SystemTemporaryDirectory, "sofaking-test-${Random.nextLong().toULong()}")
    private val file = Path(dir, "lineup-cache.bin")
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val channel = Channel("1", 1.0, "One", null, listOf("Other"))

    private var scope: CoroutineScope? = null

    @AfterTest
    fun tearDown() {
        scope?.cancel()
        if (SystemFileSystem.exists(dir)) SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(dir, mustExist = false)
    }

    /** A fresh store over the file, as a cold start opens one; the previous store is closed first, since DataStore allows one per file. */
    private suspend fun cache(): LineupCache {
        scope?.let { it.cancel(); it.coroutineContext.job.join() }
        val fresh = CoroutineScope(ioDispatcher + SupervisorJob())
        scope = fresh
        return LineupCache(lineupCacheDataStore(file.toString(), fresh), KEY) { now }
    }

    @Test
    fun `a lineup written to the file is read back by a new store`() = runTest {
        SystemFileSystem.createDirectories(dir)
        cache().write(Lineup(listOf(channel), emptyMap()))

        assertEquals(listOf(channel), assertNotNull(cache().read()).channels)
    }

    @Test
    fun `a file in the old versioned layout is replaced and reads as no lineup`() = runTest {
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(file).buffered().use { it.write(byteArrayOf(0, 0, 0, 6, 0x7f, 0x7f)) }

        assertNull(cache().read())
        assertEquals(0, SystemFileSystem.source(file).buffered().use { it.readByteArray() }.size)
    }

    private companion object {
        const val KEY = "http://server:9193"
    }
}
