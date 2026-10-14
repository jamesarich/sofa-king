package dev.jamesrich.sofaking.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/** A [ByteStore] in memory, so the codec is tested the same on every target. */
private class MemoryByteStore(var bytes: ByteArray? = null) : ByteStore {
    override fun read(): ByteArray? = bytes

    override fun write(bytes: ByteArray) {
        this.bytes = bytes
    }

    override fun delete() {
        bytes = null
    }
}

class LineupCacheTest {
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val store = MemoryByteStore()

    private companion object {
        const val KEY = "http://server:9193"
    }

    private fun cache(key: String = KEY, at: Instant = now) = LineupCache(store, key) { at }

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
    fun `round trip keeps guide status and programme metadata`() = runTest {
        val episode = Programme(
            channelId = "3",
            start = Instant.parse("2026-10-09T10:00:00Z"),
            stop = Instant.parse("2026-10-09T11:00:00Z"),
            title = "Show",
            description = null,
            subTitle = "The Pilot",
            episode = "S1 E1",
            isNew = true,
            rating = "TV-PG",
            icon = "https://img.example/show.jpg",
        )
        val lineup = Lineup(
            channels = listOf(channelOne.copy(guideStatus = GuideStatus.STALE), channelThree),
            guide = mapOf("3" to listOf(episode)),
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

        val read = assertNotNull(cache().read())

        assertEquals(listOf(channelOne, channelTwo), read.channels)
        assertEquals(mapOf("1" to listOf(airing)), read.guide)
    }

    @Test
    fun `a corrupt cache reads as null and is deleted`() = runTest {
        store.bytes = byteArrayOf(0, 0, 0, 1, 0x7f, 0x7f, 0x7f)

        assertNull(cache().read())
        assertNull(store.bytes)
    }

    @Test
    fun `a truncated cache reads as null and is deleted`() = runTest {
        cache().write(Lineup(listOf(channelOne), mapOf("1" to listOf(programme("1", "2026-10-09T11:00:00Z", "2026-10-09T12:00:00Z", "T")))))
        val bytes = assertNotNull(store.bytes)
        store.bytes = bytes.copyOf(bytes.size / 2)

        assertNull(cache().read())
        assertNull(store.bytes)
    }

    @Test
    fun `an unknown version reads as null and is deleted`() = runTest {
        cache().write(Lineup(listOf(channelOne), emptyMap()))
        assertNotNull(store.bytes)[3] = 0

        assertNull(cache().read())
        assertNull(store.bytes)
    }

    @Test
    fun `a cache written for another server is ignored and kept`() = runTest {
        cache(key = "http://a:9193").write(Lineup(listOf(channelOne), emptyMap()))

        assertNull(cache(key = "http://b:9193").read())
        assertNotNull(store.bytes)
    }

    @Test
    fun `a missing cache reads as null`() = runTest {
        assertNull(cache().read())
    }

    @Test
    fun `write replaces the previous lineup`() = runTest {
        cache().write(Lineup(listOf(channelOne, channelTwo), emptyMap()))
        cache().write(Lineup(listOf(channelThree), emptyMap()))

        assertEquals(listOf(channelThree), assertNotNull(cache().read()).channels)
    }

    @Test
    fun `a cache in the hand-written version 5 layout is deleted`() = runTest {
        store.bytes = byteArrayOf(
            0, 0, 0, 5, // version
            0, 0, 0, 1, 'k'.code.toByte(), // key
            0, 0, 0, 0, // no channels
            0, 0, 0, 0, // no guide
        )

        assertNull(cache(key = "k").read())
        assertNull(store.bytes)
    }

    @Test
    fun `a store that cannot be read reads as null`() = runTest {
        val failing = object : ByteStore {
            override fun read(): ByteArray = error("disk gone")
            override fun write(bytes: ByteArray) = Unit
            override fun delete() = Unit
        }

        assertNull(LineupCache(failing, KEY) { now }.read())
    }
}
