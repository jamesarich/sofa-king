package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.providerState
import dev.jamesrich.sofaking.backend.store.saveProviderState
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhaleClientTest {
    private val watchMojo = "523509741847643710"
    private val onlyInAll = "956148002287389583"
    private val sports = "910027172377794537"

    private fun ProviderFixture.whale(token: String = "fixture-token-1") {
        json("/api/v1/auth/access", ProviderFixture.resource("whale-auth.json").replace("fixture-token-1", token))
        route("/api/device/browser/v1/category/channels") { exchange ->
            if (exchange.requestHeaders.getFirst("token") != token) {
                Canned("""{"errorCode":"401"}""", 401)
            } else {
                Canned(ProviderFixture.resource("whale-channels.json").replace("BASE", base))
            }
        }
        json("/api/device/browser/v1/epg", ProviderFixture.resource("whale-epg.json"))
        text("/whale/master.m3u8", ProviderFixture.MASTER, "application/vnd.apple.mpegurl")
    }

    @Test fun `lists channels once per token with groups from the themed buckets`() = providerTest("sofa-whale-") {
        fixture.whale()
        val client =
            WhaleClient(
                ProviderFixture.context(
                    "whale",
                    mapOf("apiBase" to "${fixture.base}/api"),
                    http,
                    store,
                    clock,
                ),
            )
        val channels = client.channels()
        assertEquals(
            listOf(watchMojo, sports, onlyInAll),
            channels.map { it.id },
            "each channel once, themed buckets before the All bucket",
        )
        val mojo = channels.first()
        assertEquals("WatchMojo", mojo.name)
        assertEquals("News", mojo.group)
        assertEquals("0105", mojo.number)
        assertEquals(
            "https://d3b6luslimvglo.cloudfront.net/images/79/rlaxximages/channels-rescaled/" +
                "icon-white/watchmojo_white.png",
            mojo.logo,
        )
        assertEquals("Sports", channels[1].group)
        assertNull(channels[2].group, "a channel only in the All bucket has no group")
        assertEquals(1, fixture.hits("/api/v1/auth/access"))
        assertEquals(
            "fixture-token-1",
            fixture.lastHeaders["/api/device/browser/v1/category/channels"]!!["token"],
        )
        // The token lives in SQLite: a new client on the same store does not authenticate again.
        WhaleClient(
            ProviderFixture.context(
                "whale",
                mapOf("apiBase" to "${fixture.base}/api"),
                http,
                store,
                clock,
            ),
        ).channels()
        assertEquals(1, fixture.hits("/api/v1/auth/access"))
        clock.now = clock.now.plus(Duration.ofHours(24))
        WhaleClient(
            ProviderFixture.context(
                "whale",
                mapOf("apiBase" to "${fixture.base}/api"),
                http,
                store,
                clock,
            ),
        ).channels()
        assertEquals(2, fixture.hits("/api/v1/auth/access"), "an expired token is replaced")
    }

    @Test fun `a rejected token is replaced once and resolve fills every macro with a stable device id`() =
        providerTest("sofa-whale-") {
            fixture.whale(token = "fixture-token-2")
            store.saveProviderState(
                "whale",
                "token",
                "stale-token",
                clock.now.plusSeconds(3600),
                clock.now,
            )
            val client =
                WhaleClient(
                    ProviderFixture.context(
                        "whale",
                        mapOf("apiBase" to "${fixture.base}/api"),
                        http,
                        store,
                        clock,
                    ),
                )
            val resolved = client.resolve(watchMojo)
            assertEquals(
                2,
                fixture.hits("/api/device/browser/v1/category/channels"),
                "401 with the stale token, then the new one",
            )
            assertEquals(1, fixture.hits("/api/v1/auth/access"))
            assertFalse(resolved.url.contains('[') || resolved.url.contains('{'), resolved.url)
            val deviceId = store.providerState("whale", ProviderContext.DEVICE_ID)!!.value
            assertTrue(
                resolved.url.startsWith("${fixture.base}/whale/master.m3u8?did=$deviceId&sid="),
                resolved.url,
            )
            assertTrue(resolved.url.contains("&cb=${clock.now.toEpochMilli()}&ua=&dnt=0"), resolved.url)
            assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
            assertEquals("https://watch.whaletvplus.com", resolved.headers["Origin"])
            val again = WhaleClient(
                ProviderFixture.context(
                    "whale",
                    mapOf("apiBase" to "${fixture.base}/api"),
                    http,
                    store,
                    clock,
                ),
            ).resolve(watchMojo)
            assertTrue(
                again.url.contains("did=$deviceId"),
                "the device id is per install, not per process",
            )
            assertFailsWith<ProviderFailure> {
                client.resolve("nope")
            }.let { assertFalse(it.transient) }
        }

    @Test fun `guide indexes every channel and fetches programs only for wanted ids`() = providerTest("sofa-whale-") {
        fixture.whale()
        val client =
            WhaleClient(
                context("whale", mapOf("apiBase" to "${fixture.base}/api")),
            )
        val empty = client.guide(emptySet())
        assertEquals(3, empty.channels.size)
        assertTrue(empty.programs.isEmpty())
        assertEquals(0, fixture.hits("/api/device/browser/v1/epg"))
        val guide = client.guide(setOf(watchMojo))
        assertEquals(1, fixture.hits("/api/device/browser/v1/epg"))
        assertTrue(
            fixture.lastQuery["/api/device/browser/v1/epg"]!!.startsWith(
                "channelIds=$watchMojo&startTime=",
            ),
        )
        assertEquals(
            listOf("Morning Block", "Evening Block"),
            guide.programs.map { it.title },
            "a zero-length program is dropped",
        )
        assertEquals(watchMojo, guide.programs.first().channelId)
        assertEquals("WatchMojo", guide.channels.first().displayName)
    }

    @Test fun `a region asks for that country's lineup and guide and tags its channels`() = providerTest(
        "sofa-whale-",
    ) {
        fixture.whale()
        val client = WhaleClient(
            ProviderFixture.context(
                "whale-de",
                mapOf(
                    "apiBase" to "${fixture.base}/api",
                    "region" to "DE",
                ),
                http,
                store,
                MutableClock(),
            ),
        )
        assertEquals(listOf("de"), client.channels().first().tags)
        assertEquals(
            "langCode=en&countryCode=DE",
            fixture.lastQuery["/api/device/browser/v1/category/channels"],
        )
        client.guide(setOf(watchMojo))
        assertTrue(
            fixture.lastQuery["/api/device/browser/v1/epg"]!!.endsWith("&langCode=en&countryCode=DE"),
            fixture.lastQuery["/api/device/browser/v1/epg"],
        )
    }

    @Test fun `macros fill known names in every bracket form and blank the rest`() {
        val filled = Macros.fill(
            "https://x.test/p.m3u8?a=[did]&b=[%user_agent%]&c={ADID}&d=%7bUSPRIVACY%7d&e=%7BPSID%7D&f=[GDPR]&g=plain",
            mapOf("did" to "dev 1", "ADID" to "a&b", "psid" to "p"),
        )
        assertEquals("https://x.test/p.m3u8?a=dev+1&b=&c=a%26b&d=&e=p&f=&g=plain", filled)
    }
}
