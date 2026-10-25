package dev.jamesrich.sofaking.backend.admin

import dev.jamesrich.sofaking.backend.ServerConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostFactsTest {
    private val dir = Files.createTempDirectory("sofa-host-")
    private val config = ServerConfig("127.0.0.1", 8080, dir, updateImage = null)
    private val muxers = """
        File formats:
         D. = Demuxing supported
         .E = Muxing supported
         --
          E chromaprint     Chromaprint
          E matroska        Matroska
    """.trimIndent()

    @AfterTest fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test fun `the tools read the Comskip version, Chromaprint in FFmpeg and the GPU devices`() = runBlocking<Unit> {
        val commands = mutableListOf<List<String>>()
        val facts = HostFacts(config, exists = { it == "/dev/nvidiactl" }) { command ->
            commands += command
            when (command.first()) {
                "comskip" -> "Comskip 0.82.009, made using avcodec\nUsage: comskip [options] <video>"
                else -> muxers
            }
        }
        val tools = facts.tools()
        assertEquals("0.82.009", tools.comskip)
        assertEquals("in FFmpeg", tools.chromaprint)
        assertEquals("NVIDIA (/dev/nvidiactl)", tools.gpu)
        facts.tools()
        assertEquals(2, commands.size, "the tools are read once")
    }

    @Test fun `missing tools and devices say so`() = runBlocking<Unit> {
        val off = HostFacts(config.copy(comskip = null), exists = { false }) { muxers.replace("chromaprint", "other") }
        val tools = off.tools()
        assertEquals("off", tools.comskip)
        assertTrue(tools.chromaprint.startsWith("missing from FFmpeg"))
        assertEquals("none found", tools.gpu)
        assertEquals("not found at comskip", HostFacts(config, exists = { false }) { null }.tools().comskip)
    }

    @Test fun `each folder reports its disk and what the recordings take`() = runBlocking<Unit> {
        val folders = HostFacts(config).folders(recordingsUsed = 1234)
        assertEquals(listOf("Data", "Recordings", "Media"), folders.map { it.name })
        val data = folders.first()
        assertEquals(dir.toAbsolutePath().toString(), data.path)
        assertTrue(assertNotNull(data.totalBytes) > 0)
        assertNull(data.usedBytes)
        assertEquals(1234L, folders[1].usedBytes)
        assertEquals(emptyList<FolderReport>(), HostFacts(null).folders(null))
        assertNotNull(folders[2].freeBytes, "a media folder not made yet reads its parent's disk")
    }
}
