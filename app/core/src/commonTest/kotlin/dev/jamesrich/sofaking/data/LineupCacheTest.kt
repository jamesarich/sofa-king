package dev.jamesrich.sofaking.data

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class LineupCacheTest {
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val store = MemoryDataStore<CachedLineup?>(null)

    private companion object {
        const val KEY = "http://server:9193"
    }

    private fun cache(key: String = KEY, at: Instant = now) = LineupCache(store, key) { at }

    private val channelOne = Channel("1", 1.0, "One", "http://logo/1.png", listOf("Local", "News"))
    private val channelTwo = Channel("2", 2.5, "Café ☃", null, listOf("Other"))
    private val channelThree = Channel("3", 3.0, "Quiet", null, listOf("Other"))

    private fun programme(channel: String, start: String, stop: String, title: String, description: String? = null) =
        Programme(channel, Instant.parse(start), Instant.parse(stop), title, description)

    /** [bytes] as the serializer reads them from the store's file. */
    private suspend fun decode(bytes: ByteArray) = CachedLineupSerializer.readFrom(Buffer().write(bytes))

    private suspend fun encode(cached: CachedLineup?): ByteArray = Buffer().also { CachedLineupSerializer.writeTo(cached, it) }.readByteArray()

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
    fun `round trip through the serializer keeps guide status and programme metadata`() = runTest {
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
        val bytes = encode(store.value)

        assertEquals(lineup, assertNotNull(decode(bytes)).toLineup(now))
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
    fun `an empty store is no lineup and no lineup writes an empty store`() = runTest {
        assertNull(decode(ByteArray(0)))
        assertEquals(0, encode(null).size)
    }

    @Test
    fun `corrupt bytes are corruption which DataStore replaces`() = runTest {
        assertFailsWith<CorruptionException> { decode(byteArrayOf(0, 0, 0, 1, 0x7f, 0x7f, 0x7f)) }
    }

    @Test
    fun `a truncated record is corruption`() = runTest {
        cache().write(Lineup(listOf(channelOne), mapOf("1" to listOf(programme("1", "2026-10-09T11:00:00Z", "2026-10-09T12:00:00Z", "T")))))
        val bytes = encode(store.value)

        assertFailsWith<CorruptionException> { decode(bytes.copyOf(bytes.size / 2)) }
    }

    @Test
    fun `the versioned layouts before the DataStore cache are corruption`() = runTest {
        // Version 6: a big-endian version followed by the protobuf record.
        val versioned = byteArrayOf(0, 0, 0, 6) + encode(CachedLineup.of(KEY, Lineup(listOf(channelOne), emptyMap())))
        assertFailsWith<CorruptionException> { decode(versioned) }
        // Version 5: the hand-written layout.
        assertFailsWith<CorruptionException> {
            decode(byteArrayOf(0, 0, 0, 5, 0, 0, 0, 1, 'k'.code.toByte(), 0, 0, 0, 0, 0, 0, 0, 0))
        }
    }

    @Test
    fun `a record that does not make a lineup reads as null and is cleared`() = runTest {
        store.updateData { CachedLineup(KEY, channels = listOf(CachedChannel("1", 1.0, "One", null, emptyList(), "AVAILABLE"))) }

        assertNull(cache().read())
        assertNull(store.value)
    }

    @Test
    fun `a cache written for another server is ignored and kept`() = runTest {
        cache(key = "http://a:9193").write(Lineup(listOf(channelOne), emptyMap()))

        assertNull(cache(key = "http://b:9193").read())
        assertNotNull(store.value)
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
    fun `a store that cannot be read reads as null`() = runTest {
        val failing = object : androidx.datastore.core.DataStore<CachedLineup?> {
            override val data = kotlinx.coroutines.flow.flow<CachedLineup?> { error("disk gone") }
            override suspend fun updateData(transform: suspend (t: CachedLineup?) -> CachedLineup?): CachedLineup? = error("disk gone")
        }

        assertNull(LineupCache(failing, KEY) { now }.read())
    }
}
