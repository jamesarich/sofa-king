package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TubiClientTest {
    private val nfl = "613761"

    private fun ProviderFixture.tubi() {
        text("/live", ProviderFixture.resource("tubi-live.html"), "text/html")
        json("/oz/epg/programming", ProviderFixture.resource("tubi-programming.json").replace("BASE", base))
    }

    private fun options(fixture: ProviderFixture) = mapOf("pageUrl" to "${fixture.base}/live", "epgUrl" to "${fixture.base}/oz/epg/programming")

    @Test fun `lists the page's linear channels without login or DRM ones and keeps their programs`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-tubi-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.tubi()
            val client = TubiClient(ProviderFixture.context("tubi", options(fixture), http, store, MutableClock()))
            val channels = client.channels()
            assertEquals(listOf(nfl), channels.map { it.id }, "the login channel and the licensed one are left out")
            assertEquals("NFL Channel", channels[0].name); assertNull(channels[0].number)
            assertEquals("Sports on Tubi", channels[0].group, "the themed container wins over Featured")
            assertTrue(channels[0].logo!!.startsWith("https://canvas-lb.tubitv.com/"))
            assertEquals("content_id=613761,711410,555129,999999", fixture.lastQuery["/oz/epg/programming"], "themed containers first, each id once")
            val guide = client.guide(setOf(nfl))
            assertEquals(listOf("Weekly Game Previews", "NFL Game Re-Airs"), guide.programs.map { it.title })
            assertEquals(1, fixture.hits("/oz/epg/programming"), "the guide reuses the listing")
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `resolve asks for the channel alone and takes its tokenized manifest`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-tubi-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.tubi()
            val clock = MutableClock()
            val client = TubiClient(ProviderFixture.context("tubi", options(fixture), http, store, clock))
            val resolved = client.resolve(nfl)
            assertEquals("${fixture.base}/live/fixture/playlist.m3u8?token=fixture-token&tb.sid=fixture", resolved.url)
            assertEquals("content_id=$nfl", fixture.lastQuery["/oz/epg/programming"])
            assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
            assertFailsWith<ProviderFailure> { client.resolve("711410") }.let { assertFalse(it.transient, "a login channel is not listed") }
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `a page without the linear containers fails as the provider's fault`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-tubi-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.text("/live", "<html><script>window.__data = {};</script></html>", "text/html")
            val client = TubiClient(ProviderFixture.context("tubi", options(fixture), http, store, MutableClock()))
            assertFailsWith<ProviderFailure> { client.channels() }.let { assertTrue(it.transient) }
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}
