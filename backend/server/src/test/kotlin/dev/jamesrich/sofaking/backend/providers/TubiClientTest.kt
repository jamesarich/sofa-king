package dev.jamesrich.sofaking.backend.providers

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TubiClientTest {
    private val nfl = "613761"

    private fun ProviderFixture.tubi() {
        text("/live", ProviderFixture.resource("tubi-live.html"), "text/html")
        json("/oz/epg/programming", ProviderFixture.resource("tubi-programming.json").replace("BASE", base))
    }

    private fun options(fixture: ProviderFixture) = mapOf(
        "pageUrl" to "${fixture.base}/live",
        "epgUrl" to "${fixture.base}/oz/epg/programming",
    )

    @Test
    fun `lists the page's linear channels without login or DRM ones and keeps their programs`() =
        providerTest("sofa-tubi-") {
            fixture.tubi()
            val client =
                TubiClient(context("tubi", options(fixture)))
            val channels = client.channels()
            assertEquals(
                listOf(nfl),
                channels.map { it.id },
                "the login channel and the licensed one are left out",
            )
            assertEquals("NFL Channel", channels[0].name)
            assertNull(channels[0].number)
            assertEquals("Sports on Tubi", channels[0].group, "the themed container wins over Featured")
            assertTrue(channels[0].logo!!.startsWith("https://canvas-lb.tubitv.com/"))
            assertEquals(
                "content_id=613761,711410,555129,999999",
                fixture.lastQuery["/oz/epg/programming"],
                "themed containers first, each id once",
            )
            val guide = client.guide(setOf(nfl))
            assertEquals(
                listOf("Weekly Game Previews", "NFL Game Re-Airs"),
                guide.programs.map { it.title },
            )
            assertEquals(1, fixture.hits("/oz/epg/programming"), "the guide reuses the listing")
        }

    @Test fun `resolve asks for the channel alone and takes its tokenized manifest`() = providerTest("sofa-tubi-") {
        fixture.tubi()
        val client = TubiClient(ProviderFixture.context("tubi", options(fixture), http, store, clock))
        val resolved = client.resolve(nfl)
        assertEquals(
            "${fixture.base}/live/fixture/playlist.m3u8?token=fixture-token&tb.sid=fixture",
            resolved.url,
        )
        assertEquals("content_id=$nfl", fixture.lastQuery["/oz/epg/programming"])
        assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
        assertFailsWith<ProviderFailure> {
            client.resolve("711410")
        }.let { assertFalse(it.transient, "a login channel is not listed") }
    }

    @Test fun `a page without the linear containers fails as the provider's fault`() = providerTest("sofa-tubi-") {
        fixture.text("/live", "<html><script>window.__data = {};</script></html>", "text/html")
        val client =
            TubiClient(context("tubi", options(fixture)))
        assertFailsWith<ProviderFailure> { client.channels() }.let { assertTrue(it.transient) }
    }
}
