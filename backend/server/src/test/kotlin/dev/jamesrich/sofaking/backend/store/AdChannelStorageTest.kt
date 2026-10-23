package dev.jamesrich.sofaking.backend.store

import dev.jamesrich.sofaking.backend.playback.AdCensus
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class AdChannelStorageTest {
    @Test fun `plays add up per channel, viewers and listener apart, and the last marker seen is kept`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-ad-channel-test-")
        try {
            openStore(dir).use { store ->
                val first = Instant.parse("2026-10-23T18:00:00Z")
                val later = first.plusSeconds(600)
                store.recordAdChannel("c", "pluto", AdCensus(60_000, marked = true, breaks = 2, learned = 0), first)
                store.recordAdChannel("c", "pluto", AdCensus(30_000, marked = false, breaks = 0, learned = 1), later)
                store.recordAdChannel("c", "pluto", AdCensus(90_000, marked = false, breaks = 0, learned = 0), later, listened = true)
                store.recordAdChannel("d", "stirr", AdCensus(5_000, marked = false, breaks = 0, learned = 0), first, listened = true)
                val (c, d) = store.adChannels().sortedBy { it.channelId }
                assertEquals(AdChannelRecord("c", "pluto", 90_000, later, 2, 1, first, 90_000, later), c)
                assertEquals(AdChannelRecord("d", "stirr", 0, null, 0, 0, null, 5_000, first), d)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
