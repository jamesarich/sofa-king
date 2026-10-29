package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.store.providerState
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MansaClientTest {
    private fun ProviderScope.mansa(): MansaClient {
        fixture.json("/playlist", ProviderFixture.resource("mansa-playlist.json"))
        fixture.json("/epgs", ProviderFixture.resource("mansa-epgs.json"))
        return MansaClient(
            context(
                "mansa",
                mapOf("playlistUrl" to "${fixture.base}/playlist", "guideUrl" to "${fixture.base}/epgs"),
            ),
        )
    }

    @Test fun `lists the linear channels and leaves out on-demand playlists`() = providerTest("sofa-mansa-") {
        val client = mansa()
        val channels = client.channels()
        assertEquals(
            listOf("The Black Effect", "Judge Faith", "Manifest TV", "Stingray Hip Hop"),
            channels.map { it.name },
            "The Joint is a VIRTUAL playlist",
        )
        val first = channels.first()
        assertEquals("1106544567159898165", first.id)
        assertEquals("Movies", first.group)
        assertEquals(listOf("us"), first.tags)
        assertEquals(
            "https://kanku-channel-images.s3.amazonaws.com/the_black_effect_unselected_state_image.png",
            first.logo,
        )
        assertEquals("""{"size":200}""", fixture.lastBody["/playlist"])
        assertEquals("https://www.mansa.com", fixture.lastHeaders["/playlist"]!!["origin"])
    }

    @Test fun `resolve fills the ad macros and leaves a plain URL alone`() = providerTest("sofa-mansa-") {
        val client = mansa()
        val resolved = client.resolve("1106544567159898165")
        val deviceId = store.providerState("mansa", ProviderContext.DEVICE_ID)!!.value
        assertTrue("ads.device_id=$deviceId&" in resolved.url, resolved.url)
        assertTrue("ads.us_privacy=1---&" in resolved.url, resolved.url)
        assertTrue("ads.consent=&" in resolved.url, "an unknown macro fills empty")
        assertFalse("%7B" in resolved.url || "{" in resolved.url, resolved.url)
        assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
        assertEquals(
            "https://amg18477-mycode-amg18477c1-mansa-us-9705.playouts.now.amagi.tv/playlist/" +
                "amg18477-mycode-judgefaith-mansaus/playlist.m3u8",
            client.resolve("430133871780907771").url,
        )
        assertEquals(1, fixture.hits("/playlist"), "channels and both resolves share one listing")
    }

    @Test fun `guide matches rows by channel id, falls back to the name and drops finished airings`() =
        providerTest("sofa-mansa-") {
            val client = mansa()
            val wanted = setOf("1106544567159898165", "-134915139385471409")
            val guide = client.guide(wanted)
            assertEquals(4, guide.channels.size)
            assertEquals(
                listOf("Current Movie", "Next Movie", "Manifest Hour"),
                guide.programs.map { it.title },
                "the finished airing, the unwanted channel and the unlisted one are dropped",
            )
            assertEquals(
                setOf("1106544567159898165", "-134915139385471409"),
                guide.programs.map { it.channelId }.toSet(),
                "ManifestTV in the feed is Manifest TV in the playlist",
            )
            val current = guide.programs.first()
            assertEquals("2026-10-10T12:00:00Z", current.startsAt.toString())
            assertEquals("A description.", current.description)
            assertEquals("https://example.invalid/2.jpg", current.icon)
            assertTrue(guide.programs.all { it.endsAt > it.startsAt })
        }

    @Test fun `guide fetches nothing when no listed channel is wanted`() = providerTest("sofa-mansa-") {
        val client = mansa()
        assertTrue(client.guide(setOf("unknown")).programs.isEmpty())
        assertEquals(0, fixture.hits("/epgs"))
    }

    @Test fun `a failing service is a transient failure`() = providerTest("sofa-mansa-") {
        val client = mansa()
        fixture.route("/playlist") { Canned("busy", 503, "text/plain") }
        val failure = assertFailsWith<ProviderFailure> { client.channels() }
        assertTrue(failure.transient)
        assertFalse(fixture.base in failure.message.orEmpty(), "no URL in the message")
        fixture.json("/playlist", """{"code": "ok", "channels": []}""")
        assertTrue(assertFailsWith<ProviderFailure> { client.channels() }.transient, "an empty list is a fault")
    }

    @Test fun `a channel that is no longer listed is a permanent failure`() = providerTest("sofa-mansa-") {
        val client = mansa()
        val failure = assertFailsWith<ProviderFailure> { client.resolve("-1") }
        assertFalse(failure.transient)
        assertEquals(2, fixture.hits("/playlist"), "the listing is read again once before giving up")
    }
}
