package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.store.providerState
import java.net.URLEncoder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val SCHEDULE_LIST = "/api/v1.0/schedulelist"

class LgClientTest {
    /** The client over the fixture's schedule list: the real base64-of-zlib shape, rebuilt for this server. */
    private fun ProviderScope.lg(): LgClient {
        // The fixture has a BASE placeholder inside; rebuild it for this server's address.
        val json = LgClient.decode(ProviderFixture.resource("lg-schedulelist.b64")).toString()
            .replace("BASE", fixture.base)
        val deflated = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(json.toByteArray()) }
        }.toByteArray()
        fixture.text(
            SCHEDULE_LIST,
            java.util.Base64.getEncoder().encodeToString(deflated),
            "text/plain;charset=UTF-8",
        )
        return LgClient(context("lg", mapOf("apiBase" to "${fixture.base}/api/v1.0")))
    }

    @Test fun `decodes the base64 zlib schedulelist into channels, with the device headers`() = providerTest(
        "sofa-lg-",
    ) {
        val channels = lg().channels()
        assertEquals(
            listOf("foxlivenow_foxlivenow_US", "FREQ847-US"),
            channels.map { it.id },
            "a channel in two categories is listed once",
        )
        val fox = channels.first()
        assertEquals("LiveNOW from FOX", fox.name)
        assertEquals("123", fox.number)
        assertEquals("News", fox.group)
        assertEquals(
            "https://d3rimcir91uaav.cloudfront.net/196481_LG_LiveNow_Logo_White_800x200_001.png",
            fox.logo,
        )
        val sent = fixture.lastHeaders[SCHEDULE_LIST]!!
        assertEquals("US", sent["x-device-country"])
        assertEquals("WEB", sent["x-device-type"])
        assertEquals("https://lgchannels.com", sent["origin"])
    }

    @Test fun `the guide comes from the same schedulelist`() = providerTest("sofa-lg-") {
        val client = lg()
        client.channels()
        val guide = client.guide(setOf("foxlivenow_foxlivenow_US"))
        assertEquals(2, guide.channels.size)
        assertEquals(
            listOf("2026-10-10T22:00:00Z", "2026-10-11T02:00:00Z"),
            guide.programs.map { it.startsAt.toString() },
        )
        assertEquals("LiveNOW from FOX", guide.programs.first().title)
        assertEquals(1, fixture.hits(SCHEDULE_LIST))
    }

    @Test fun `resolve fills the stream URL, and an unknown id refreshes the list once`() = providerTest("sofa-lg-") {
        val client = lg()
        client.channels()
        val resolved = client.resolve("foxlivenow_foxlivenow_US")
        val deviceId = store.providerState("lg", ProviderContext.DEVICE_ID)!!.value
        val ua = URLEncoder.encode(BROWSER_USER_AGENT, Charsets.UTF_8)
        assertEquals(
            "${fixture.base}/lg/master.m3u8?ads.deviceid=$deviceId&ads.ua=$ua&ads.country=US&" +
                "ads.nonce=${clock.now.epochSecond}&ads.hotel=&ads.ifa=",
            resolved.url,
        )
        assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
        assertEquals("https://lgchannels.com/", resolved.headers["Referer"])
        assertNull(resolved.headers["x-device-country"], "device headers belong to the API, not the CDN")
        assertEquals(1, fixture.hits(SCHEDULE_LIST))
        assertFailsWith<ProviderFailure> { client.resolve("missing") }.let { assertFalse(it.transient) }
        assertEquals(2, fixture.hits(SCHEDULE_LIST), "an unknown id refreshes the list once before giving up")
    }

    @Test fun `plain JSON and malformed documents are handled`() {
        assertEquals("x", LgClient.decode("""{"categories":[],"a":"x"}""")["a"].string)
        assertFailsWith<ProviderFailure> { LgClient.decode("not base64 at all!!") }
        assertFailsWith<ProviderFailure> {
            LgClient.decode(java.util.Base64.getEncoder().encodeToString("plain bytes".toByteArray()))
        }
    }
}
