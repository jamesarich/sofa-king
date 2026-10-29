package dev.jamesrich.sofaking.data

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileCrashStoreTest {
    private val dir = testDirectory()
    private val store = FileCrashStore(Path(dir, "crashes").toString())

    @AfterTest
    fun tearDown() {
        val crashes = Path(dir, "crashes")
        if (SystemFileSystem.exists(crashes)) SystemFileSystem.list(crashes).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(crashes, mustExist = false)
        SystemFileSystem.delete(dir, mustExist = false)
    }

    @Test
    fun `a store with no directory has nothing pending`() {
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `crashes come back oldest first with their text`() {
        store.save("002-b", """{"id":"b"}""")
        store.save("001-a", """{"id":"a"}""")
        assertEquals(listOf("001-a", "002-b"), store.pending().map { it.key })
        assertEquals("""{"id":"a"}""", store.pending().first().json)
    }

    @Test
    fun `a removed crash is gone and removing it again is harmless`() {
        store.save("001-a", "{}")
        store.remove("001-a")
        store.remove("001-a")
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a recorder over the files keeps only the newest crashes`() {
        var tick = 1L
        var n = 0
        val recorder = CrashRecorder(
            store,
            CrashContext("1", "desktop", "Linux", "x86_64"),
            keep = 2,
            now = { kotlin.time.Instant.fromEpochMilliseconds(tick++) },
            newId = { "id-${n++}" },
        )
        repeat(4) { recorder.record("java.lang.IllegalStateException", "m$it", "") }
        assertEquals(2, store.pending().size)
        assertTrue(store.pending().last().json.contains("m3"))
    }
}
