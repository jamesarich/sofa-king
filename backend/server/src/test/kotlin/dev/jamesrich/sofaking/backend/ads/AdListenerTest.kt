package dev.jamesrich.sofaking.backend.ads

import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.lineupStack
import dev.jamesrich.sofaking.backend.playback.SegmentAudio
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.backend.store.AdChannelRecord
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdListenerTest {
    private val now = Instant.parse("2026-10-23T18:00:00Z")
    private fun record(id: String, watchedDaysAgo: Long?, marked: Boolean, heardMinutesAgo: Long? = null) =
        AdChannelRecord(
            id, "p", 0,
            watchedDaysAgo?.let {
                now.minusSeconds(it * 86_400)
            },
            0, 0, if (marked) now else null, 0, heardMinutesAgo?.let { now.minusSeconds(it * 60) },
        )

    @Test
    fun `recent channels without markers come first, then recent ones with markers, unheard, least recently heard`() {
        val known = listOf(
            record("old-unmarked", watchedDaysAgo = 30, marked = false, heardMinutesAgo = 600),
            record("unmarked-yesterday", watchedDaysAgo = 1, marked = false),
            record("unmarked-today", watchedDaysAgo = 0, marked = false),
            record("unmarked-just-heard", watchedDaysAgo = 0, marked = false, heardMinutesAgo = 5),
            record("marked-today", watchedDaysAgo = 0, marked = true),
            record("heard-long-ago", watchedDaysAgo = null, marked = false, heardMinutesAgo = 9_000),
        )
        val lineup = known.map { it.channelId } + "never-heard" + "watched-now"
        assertEquals(
            listOf(
                "unmarked-today",
                "unmarked-yesterday",
                "unmarked-just-heard",
                "marked-today",
                "never-heard",
                "heard-long-ago",
                "old-unmarked",
            ),
            AdListener.pick(known, lineup, busy = setOf("watched-now"), now = now),
        )
        assertEquals(
            listOf("unmarked-today", "unmarked-yesterday"),
            AdListener.pick(known, lineup, emptySet(), now = now).take(2),
        )
    }

    @Test
    fun `fewer listen while anyone watches and none with learned breaks off`() {
        assertEquals(6, AdListener.wanted(LearnedBreaks.SHADOW, emptySet(), 6))
        assertEquals(AdListener.WHILE_WATCHING, AdListener.wanted(LearnedBreaks.ON, setOf("news"), 6))
        assertEquals(1, AdListener.wanted(LearnedBreaks.SHADOW, setOf("news"), 1))
        assertEquals(0, AdListener.wanted(LearnedBreaks.OFF, emptySet(), 6))
    }

    @Test
    fun `a fetch runs under the listener's own pool`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-ad-listener-pool-")
        try {
            MediaFixture().use { media ->
                HttpClient(CIO).use { http ->
                    openStore(dir).use { store ->
                        val clock = MutableClock()
                        val feeds = FeedService(
                            emptyList(),
                            store,
                            http,
                            clock,
                            UpstreamPolicy(allowLoopback = true),
                        )
                        val catalog = lineupStack(feeds, store, clock, http, adopt = false).catalog
                        val pool = Semaphore(1)
                        val listener = AdListener(
                            http, feeds, SessionManager(catalog, store, clock), store, BreakLibrary(store, clock),
                            SegmentAudio("ffmpeg"), pool, clock, { emptyList() }, { 0 }, { LearnedBreaks.OFF },
                        )
                        val stream = ResolvedStream("${media.base}/vod.m3u8", emptyMap())
                        assertNotNull(listener.fetchText(stream, stream.url), "a free pool serves the fetch")
                        assertEquals(1, pool.availablePermits, "the permit is given back")
                        pool.acquire()
                        val waiting = async { listener.fetchText(stream, stream.url) }
                        delay(500)
                        assertTrue(waiting.isActive, "the fetch waits for the listener's pool")
                        pool.release()
                        assertNotNull(withTimeout(10_000) { waiting.await() })
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the lowest variant is followed, or its audio rendition`() {
        val base = URI("https://cdn.example.test/live/master.m3u8")
        val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="audio/en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=3000000,AUDIO="aac"
hi/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="aac"
lo/index.m3u8
"""
        assertEquals("https://cdn.example.test/live/audio/en.m3u8", AdListener.mediaOf(master, base))
        assertEquals(
            "https://cdn.example.test/live/lo/index.m3u8",
            AdListener.mediaOf(master.replace("AUDIO=\"aac\"", ""), base),
        )
        assertEquals(base.toString(), AdListener.mediaOf("#EXTM3U\n#EXTINF:6,\na.ts\n", base))
    }
}
