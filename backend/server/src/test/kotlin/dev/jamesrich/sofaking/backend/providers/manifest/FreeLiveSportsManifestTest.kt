package dev.jamesrich.sofaking.backend.providers.manifest

import dev.jamesrich.sofaking.backend.providers.ProviderContext
import dev.jamesrich.sofaking.backend.providers.ProviderFixture
import dev.jamesrich.sofaking.backend.providers.providerTest
import dev.jamesrich.sofaking.backend.store.providerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreeLiveSportsManifestTest {
    @Test fun `one catalog document yields channels in number order, the embedded schedule and fresh macros`() =
        providerTest("sofa-fls-") {
            fixture.json(
                "/catalog/key",
                ProviderFixture.resource("freelivesports-catalog.json").replace("BASE", fixture.base),
            )
            val client = manifestClient(
                "fls",
                "freelivesports",
                mapOf("catalogUrl" to "${fixture.base}/catalog/key?__site=freelivesports&__source=web"),
            )
            val channels = client.channels()
            assertEquals(
                listOf("Overtime", "MSG Sports Zone"),
                channels.map { it.name },
                "sorted by channel number, not document order",
            )
            assertEquals(listOf("1", "2"), channels.map { it.number })
            assertEquals("Sports", channels.first().group)
            assertEquals(
                "https://media.unreel.me/prod/freelivesports/logo/e23d456b-edc1-44c6-a4e0-b90fe7bf967e",
                channels.first().logo,
            )
            assertEquals("__site=freelivesports&__source=web", fixture.lastQuery["/catalog/key"])
            assertEquals(
                "https://www.freelivesports.tv",
                fixture.lastHeaders["/catalog/key"]!!["origin"],
            )
            val guide = checkNotNull(client.guide(setOf("6902789463cbad62d1ce4a43")))
            assertEquals(2, guide.programs.size)
            assertEquals("UMass-Lowell at Binghamton", guide.programs.first().title)
            assertEquals(
                "From Bearcats Sports Complex in Binghamton, N.Y.",
                guide.programs.first().description,
            )
            assertTrue(checkNotNull(client.guide(setOf("69658066975ee235f015da2b"))).programs.isEmpty())
            val resolved = client.resolve("6902789463cbad62d1ce4a43")
            val deviceId = store.providerState("fls", ProviderContext.DEVICE_ID)!!.value
            assertEquals(
                "${fixture.base}/fls/master.m3u8?ads.us_privacy=&ads.device_id=$deviceId&" +
                    "ads.cb=${clock.now.epochSecond}&ads.ref=https%3A%2F%2Fwww.freelivesports.tv%2F",
                resolved.url,
            )
            clock.now = clock.now.plusSeconds(5)
            assertTrue(
                client.resolve(
                    "6902789463cbad62d1ce4a43",
                ).url.contains("ads.cb=${clock.now.epochSecond}"),
                "the cache buster is fresh per resolve",
            )
            assertEquals(1, fixture.hits("/catalog/key"))
        }

    @Test fun `a catalog that is an object with a channels array lists the same channels`() = providerTest(
        "sofa-fls-",
    ) {
        val rows = ProviderFixture.resource("freelivesports-catalog.json").replace("BASE", fixture.base)
        fixture.json("/catalog/key", "{\"channels\": $rows}")
        val client = manifestClient("fls", "freelivesports", mapOf("catalogUrl" to "${fixture.base}/catalog/key"))
        assertEquals(listOf("Overtime", "MSG Sports Zone"), client.channels().map { it.name })
    }
}
