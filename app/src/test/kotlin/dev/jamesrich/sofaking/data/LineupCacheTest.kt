package dev.jamesrich.sofaking.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class LineupCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = Instant.parse("2026-10-09T10:00:00Z")

    private companion object {
        const val KEY = "http://server:9193"
    }

    private fun cacheFile(): File = File(tempFolder.root, "lineup-cache.bin")

    private fun cache(key: String = KEY, at: Instant = now) = LineupCache(cacheFile(), key) { at }

    private val channelOne = Channel("1", 1.0, "One", "http://logo/1.png", listOf("Local", "News"))
    private val channelTwo = Channel("2", 2.5, "Café ☃", null, listOf("Other"))
    private val channelThree = Channel("3", 3.0, "Quiet", null, listOf("Other"))

    private fun programme(channel: String, start: String, stop: String, title: String, description: String? = null) =
        Programme(channel, Instant.parse(start), Instant.parse(stop), title, description)

    @Test
    fun `round trip keeps channels and programmes`() = runTest {
        val lineup = Lineup(
            channels = listOf(channelOne, channelTwo),
            guide = mapOf(
                "1" to listOf(
                    programme("1", "2026-10-09T09:00:00Z", "2026-10-09T11:00:00Z", "Morning", "Long description"),
                    programme("1", "2026-10-09T11:00:00Z", "2026-10-09T12:00:00Z", "Noon ✓ naïve"),
                ),
                "2" to listOf(programme("2", "2026-10-09T10:00:00Z", "2026-10-09T10:30:00Z", "Short", description = "d")),
            ),
        )

        cache().write(lineup)

        assertEquals(lineup, cache().read())
    }

    @Test
    fun `read drops programmes that have already ended`() = runTest {
        val ended = programme("1", "2026-10-09T08:00:00Z", "2026-10-09T09:00:00Z", "Ended")
        val boundary = programme("1", "2026-10-09T09:00:00Z", "2026-10-09T10:00:00Z", "Ends now")
        val airing = programme("1", "2026-10-09T09:30:00Z", "2026-10-09T11:00:00Z", "Airing")
        val allEnded = programme("2", "2026-10-09T07:00:00Z", "2026-10-09T08:00:00Z", "Gone")
        cache().write(
            Lineup(
                listOf(channelOne, channelTwo),
                mapOf("1" to listOf(ended, boundary, airing), "2" to listOf(allEnded)),
            ),
        )

        val read = cache().read()!!

        assertEquals(listOf(channelOne, channelTwo), read.channels)
        assertEquals(mapOf("1" to listOf(airing)), read.guide)
    }

    @Test
    fun `a corrupt file reads as null and is deleted`() = runTest {
        cacheFile().writeBytes(byteArrayOf(0, 0, 0, 1, 0x7f, 0x7f, 0x7f))

        assertNull(cache().read())
        assertFalse(cacheFile().exists())
    }

    @Test
    fun `a truncated file reads as null and is deleted`() = runTest {
        cache().write(Lineup(listOf(channelOne), mapOf("1" to listOf(programme("1", "2026-10-09T11:00:00Z", "2026-10-09T12:00:00Z", "T")))))
        val bytes = cacheFile().readBytes()
        cacheFile().writeBytes(bytes.copyOf(bytes.size / 2))

        assertNull(cache().read())
        assertFalse(cacheFile().exists())
    }

    @Test
    fun `an unknown version reads as null and is deleted`() = runTest {
        cache().write(Lineup(listOf(channelOne), emptyMap()))
        val bytes = cacheFile().readBytes()
        bytes[3] = 0
        cacheFile().writeBytes(bytes)

        assertNull(cache().read())
        assertFalse(cacheFile().exists())
    }

    @Test
    fun `a file written for another server is ignored and kept`() = runTest {
        cache(key = "http://a:9193").write(Lineup(listOf(channelOne), emptyMap()))

        assertNull(cache(key = "http://b:9193").read())
        assertTrue(cacheFile().exists())
    }

    @Test
    fun `a missing file reads as null`() = runTest {
        assertNull(cache().read())
    }

    @Test
    fun `write replaces the previous lineup and leaves no temp file`() = runTest {
        cache().write(Lineup(listOf(channelOne, channelTwo), emptyMap()))
        cache().write(Lineup(listOf(channelThree), emptyMap()))

        assertEquals(listOf(channelThree), cache().read()!!.channels)
        assertEquals(listOf("lineup-cache.bin"), tempFolder.root.list()!!.toList())
    }
}
