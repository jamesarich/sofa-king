package dev.jamesrich.sofaking.data

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileByteStoreTest {
    private val dir = Path(SystemTemporaryDirectory, "sofaking-test-${Random.nextLong().toULong()}")
    private val file = Path(dir, "nested", "lineup-cache.bin")

    @AfterTest
    fun tearDown() {
        val parent = file.parent ?: return
        if (SystemFileSystem.exists(parent)) SystemFileSystem.list(parent).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(parent, mustExist = false)
        SystemFileSystem.delete(dir, mustExist = false)
    }

    @Test
    fun `a missing file reads as null`() {
        assertNull(FileByteStore(file).read())
    }

    @Test
    fun `write creates its directory and replaces the file leaving no temp file`() {
        val store = FileByteStore(file)

        store.write(byteArrayOf(1, 2, 3))
        store.write(byteArrayOf(4))

        assertContentEquals(byteArrayOf(4), store.read())
        assertEquals(listOf("lineup-cache.bin"), SystemFileSystem.list(file.parent!!).map { it.name })
    }

    @Test
    fun `delete removes the file and tolerates its absence`() {
        val store = FileByteStore(file)
        store.write(byteArrayOf(1))

        store.delete()
        store.delete()

        assertFalse(SystemFileSystem.exists(file))
    }
}
