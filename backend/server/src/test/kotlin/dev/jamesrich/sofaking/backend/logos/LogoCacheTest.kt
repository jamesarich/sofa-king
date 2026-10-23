package dev.jamesrich.sofaking.backend.logos

import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.logos
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogoCacheTest {
    private val loopback = UpstreamPolicy(allowLoopback = true)

    private fun cache(
        store: BackendStore,
        http: HttpClient,
        clock: MutableClock,
        dir: Path,
        wanted: () -> List<String>,
        policy: UpstreamPolicy = loopback,
    ) = LogoCache(store, http, clock, dir.resolve("logos"), policy, wanted)

    private fun <T> withFixture(block: suspend (Fixture, HttpClient, Path) -> T): T = runBlocking {
        val dir = Files.createTempDirectory("sofa-logos-")
        try {
            Fixture().use { fixture ->
                HttpClient(CIO) { followRedirects = false }.use { http -> block(fixture, http, dir) }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a logo is fetched once, served from the file afterwards and survives a restart`() = withFixture {
            fixture,
            http,
            dir,
        ->
        val clock = MutableClock()
        val key = LogoCache.keyOf(fixture.logoUrl)
        openStore(dir).use { store ->
            val logos = cache(store, http, clock, dir, { listOf(fixture.logoUrl) })
            logos.initialize()
            assertNull(logos.lookup("0".repeat(64)), "an unknown key is never fetched")
            val record = assertNotNull(logos.lookup(key))
            assertEquals("image/png", record.contentType)
            assertEquals(Fixture.PNG.size.toLong(), record.size)
            assertEquals(clock.now, record.fetchedAt)
            assertContentEquals(Fixture.PNG, logos.read(record))
            assertContentEquals(Fixture.PNG, Files.readAllBytes(dir.resolve("logos").resolve(key)))
            assertEquals(1, fixture.logoRequests.get())
            assertEquals(record, logos.lookup(key))
            assertEquals(1, fixture.logoRequests.get(), "a cache hit fetches nothing")
            assertEquals(listOf(record), store.logos())
        }
        openStore(dir).use { store ->
            val logos = cache(store, http, clock, dir, { listOf(fixture.logoUrl) })
            logos.initialize()
            assertEquals("image/png", logos.lookup(key)?.contentType)
            assertEquals(1, fixture.logoRequests.get(), "the restarted server serves the stored file")
        }
    }

    @Test fun `the body decides the type, and anything that is not an image is refused and remembered`() = withFixture {
            fixture,
            http,
            dir,
        ->
        val clock = MutableClock()
        val svg = "${fixture.base}/logo.svg"
        val mislabeled = "${fixture.base}/logo-mislabeled.jpg"
        val decoy = "${fixture.base}/decoy.png"
        val script = "${fixture.base}/decoy-script.png"
        val huge = "${fixture.base}/logo-huge.png"
        openStore(dir).use { store ->
            val logos = cache(store, http, clock, dir, { listOf(svg, mislabeled, decoy, script, huge) })
            logos.initialize()
            val stored = assertNotNull(logos.lookup(LogoCache.keyOf(svg)))
            assertEquals("image/svg+xml", stored.contentType)
            assertContentEquals(Fixture.SVG.toByteArray(), logos.read(stored), "an SVG is stored byte for byte")
            assertEquals("image/png", logos.lookup(LogoCache.keyOf(mislabeled))?.contentType)
            for (refused in listOf(decoy, script, huge)) {
                val key = LogoCache.keyOf(refused)
                assertNull(logos.lookup(key), refused)
                assertFalse(Files.exists(dir.resolve("logos").resolve(key)), refused)
                val record = assertNotNull(logos.record(key), refused)
                assertFalse(record.cached)
                assertEquals(clock.now, record.failedAt)
                assertEquals(1, record.failures)
            }
            val before = fixture.logoRequests.get()
            assertNull(logos.lookup(LogoCache.keyOf(decoy)))
            assertEquals(before, fixture.logoRequests.get(), "a failure is not retried within the hour")
            clock.now += Duration.ofHours(1)
            assertNull(logos.lookup(LogoCache.keyOf(decoy)))
            assertEquals(before + 1, fixture.logoRequests.get())
            assertEquals(2, logos.record(LogoCache.keyOf(decoy))?.failures)
            assertTrue(
                Files.list(dir.resolve("logos")).use {
                    it.noneMatch { path -> path.fileName.toString().endsWith(".tmp") }
                },
            )
        }
    }

    @Test fun `the address policy governs logo fetches`() = withFixture { fixture, http, dir ->
        openStore(dir).use { store ->
            val logos = cache(store, http, MutableClock(), dir, { listOf(fixture.logoUrl) }, policy = UpstreamPolicy())
            logos.initialize()
            assertNull(logos.lookup(LogoCache.keyOf(fixture.logoUrl)))
            assertEquals(0, fixture.logoRequests.get(), "a loopback logo is refused before any request")
        }
    }

    @Test fun `a sweep fetches what is missing, refreshes weekly, keeps the old file when the refresh fails, and drops the unwanted`() =
        withFixture {
                fixture,
                http,
                dir,
            ->
            val clock = MutableClock()
            val copy = "${fixture.base}/logo-copy.png"
            var wanted = listOf(fixture.logoUrl, copy)
            openStore(dir).use { store ->
                val logos = cache(store, http, clock, dir, { wanted })
                logos.initialize()
                logos.sweep()
                assertEquals(2, fixture.logoRequests.get())
                assertTrue(logos.record(LogoCache.keyOf(copy))!!.cached)
                logos.sweep()
                assertEquals(2, fixture.logoRequests.get(), "fresh logos are not fetched again")
                // A week on, a lookup still serves the cached file and the sweep refreshes it.
                clock.now += Duration.ofDays(7)
                val key = LogoCache.keyOf(fixture.logoUrl)
                fixture.logoBroken = true
                assertEquals("image/png", logos.lookup(key)?.contentType)
                logos.sweep()
                assertEquals(4, fixture.logoRequests.get())
                val stale = assertNotNull(logos.record(key))
                assertTrue(stale.cached, "a failed refresh keeps the cached file")
                assertEquals(clock.now, stale.failedAt)
                assertEquals(clock.now - Duration.ofDays(7), stale.fetchedAt)
                assertContentEquals(Fixture.PNG, logos.read(stale))
                logos.sweep()
                assertEquals(4, fixture.logoRequests.get(), "the failed refresh is not retried within the hour")
                clock.now += Duration.ofHours(1)
                fixture.logoBroken = false
                logos.sweep()
                assertEquals(5, fixture.logoRequests.get())
                assertEquals(clock.now, logos.record(key)!!.fetchedAt)
                assertNull(logos.record(key)!!.failedAt)
                // The lineup drops one URL: its row and file go, the other stays.
                wanted = listOf(fixture.logoUrl)
                logos.sweep()
                assertNull(logos.record(LogoCache.keyOf(copy)))
                assertFalse(Files.exists(dir.resolve("logos").resolve(LogoCache.keyOf(copy))))
                assertTrue(Files.exists(dir.resolve("logos").resolve(key)))
                assertEquals(listOf(key), store.logos().map { it.key })
                assertNull(logos.lookup(LogoCache.keyOf(copy)), "a dropped logo is not fetched for a stale playlist")
                assertEquals(5, fixture.logoRequests.get())
            }
        }

    @Test fun `image signatures are recognized and the rest is not an image`() {
        assertEquals("image/png", LogoCache.rasterType(Fixture.PNG))
        assertEquals(
            "image/jpeg",
            LogoCache.rasterType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())),
        )
        assertEquals("image/gif", LogoCache.rasterType("GIF89a".toByteArray()))
        assertEquals(
            "image/webp",
            LogoCache.rasterType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)),
        )
        assertNull(LogoCache.rasterType("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)))
        assertNull(LogoCache.rasterType(Fixture.SVG.toByteArray()))
        assertTrue(LogoCache.looksLikeMarkup(Fixture.SVG.toByteArray()))
        assertTrue(LogoCache.looksLikeMarkup("  <svg/>".toByteArray()))
        assertFalse(LogoCache.looksLikeMarkup(Fixture.PNG))
        assertFalse(LogoCache.looksLikeMarkup(ByteArray(0)))
    }

    @Test fun `a stored reference with an unencoded space is fetched, and one that is no URL is a failure row`() =
        withFixture {
                fixture,
                http,
                dir,
            ->
            val clock = MutableClock()
            val spaced = "${fixture.base}/logo copy.png"
            val relative = "/logo.png"
            openStore(dir).use { store ->
                val logos = cache(store, http, clock, dir, { listOf(spaced, relative) })
                logos.initialize()
                val record = assertNotNull(logos.lookup(LogoCache.keyOf(spaced)))
                assertEquals("image/png", record.contentType)
                assertEquals(
                    spaced,
                    record.sourceUrl,
                    "the key and the row keep the reference as the lineup carries it",
                )
                assertEquals(1, fixture.logoRequests.get())
                assertNull(logos.lookup(LogoCache.keyOf(relative)))
                assertEquals(1, fixture.logoRequests.get(), "a reference that is no URL is refused before any request")
                assertEquals(1, logos.record(LogoCache.keyOf(relative))?.failures)
            }
        }
}
