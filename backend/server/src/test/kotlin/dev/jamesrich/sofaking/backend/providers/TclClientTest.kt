package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.providerState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TclClientTest {
    private val news = "640822369463833685"
    private val top = "640822369463571541"

    private fun ProviderFixture.tcl() {
        json("/api/metadata/v2/livetab", ProviderFixture.resource("tcl-livetab.json"))
        route("/api/metadata/v1/epg/programlist/by/category") { exchange ->
            when (exchange.requestURI.rawQuery.substringAfter("category_id=").substringBefore("&")) {
                news -> Canned(ProviderFixture.resource("tcl-news.json").replace("BASE", base))
                top -> Canned(ProviderFixture.resource("tcl-top.json"))
                else -> Canned("""{"error":"recommend rows are personal"}""", 500)
            }
        }
        json(
            "/api/metadata/v1/format-stream-url",
            """{"stream_url":"$base/tcl/master.m3u8?device%5Bdid%5D=REPLACE_ME&ads.device_id=%7BDEVICE_ID%7D&ads.us_privacy=1YN-&ads.app_name=%7BAPP_NAME%7D&ads.ifa=%5BIFA%5D"}""",
        )
    }

    @Test
    fun `lists channels from the themed rows first and keeps their programmes for the guide`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-tcl-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.tcl()
                        val clock = MutableClock()
                        val client =
                            TclClient(
                                ProviderFixture.context("tcl", mapOf("apiBase" to fixture.base), http, store, clock),
                            )
                        val channels = client.channels()
                        assertEquals(
                            listOf("XU233757", "CB502647", "XU431754"),
                            channels.map {
                                it.id
                            },
                            "the drm channel is left out, the recommend row never read",
                        )
                        assertEquals("NBC News Now", channels[0].name)
                        assertNull(channels[0].number)
                        assertEquals("News & Opinion", channels[0].group)
                        assertEquals(
                            "News & Opinion",
                            channels[1].group,
                            "a channel in a curated row and a themed row takes the themed one",
                        )
                        assertNull(channels[2].group, "a channel only in a curated row has no group")
                        assertTrue(channels[0].logo!!.startsWith("https://tcl-channel-cdn.ideonow.com/static/channel/"))
                        val deviceId = store.providerState("tcl", ProviderContext.DEVICE_ID)!!.value
                        val query = fixture.lastQuery["/api/metadata/v1/epg/programlist/by/category"]!!
                        assertTrue(
                            query.contains("device_id=$deviceId") &&
                                query.endsWith("start=2026-10-10T12:00:00Z&end=2026-10-11T00:00:00Z"),
                            query,
                        )
                        assertEquals(2, fixture.hits("/api/metadata/v1/epg/programlist/by/category"))
                        val guide = client.guide(setOf("XU233757"))
                        assertEquals(3, guide.channels.size)
                        assertEquals(
                            listOf("Dateline NBC S27 E26", "Dateline NBC S27 E35"),
                            guide.programmes.map {
                                it.title
                            },
                        )
                        assertTrue(guide.programmes.all { it.channelId == "XU233757" })
                        assertEquals(
                            2,
                            fixture.hits("/api/metadata/v1/epg/programlist/by/category"),
                            "the guide reuses the listing",
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `resolve has the service format the template and fills only the braces it leaves`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-tcl-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.tcl()
                        val clock = MutableClock()
                        val client =
                            TclClient(
                                ProviderFixture.context("tcl", mapOf("apiBase" to fixture.base), http, store, clock),
                            )
                        val resolved = client.resolve("XU233757")
                        val deviceId = store.providerState("tcl", ProviderContext.DEVICE_ID)!!.value
                        assertEquals(
                            "${fixture.base}/tcl/master.m3u8?device%5Bdid%5D=REPLACE_ME&ads.device_id=$deviceId&ads.us_privacy=1YN-&ads.app_name=&ads.ifa=%5BIFA%5D",
                            resolved.url,
                        )
                        assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
                        val body = Json.parseToJsonElement(
                            fixture.lastBody["/api/metadata/v1/format-stream-url"]!!,
                        ).jsonObject
                        assertEquals("XU233757", body["bundle_id"]!!.jsonPrimitive.content)
                        assertEquals("Xumo", body["source"]!!.jsonPrimitive.content)
                        assertEquals(deviceId, body["device_id"]!!.jsonPrimitive.content)
                        assertTrue(
                            body["stream_url"]!!.jsonPrimitive.content.startsWith(
                                "${fixture.base}/tcl/master.m3u8?ads.xumo_ifa=%5BIFA%5D",
                            ),
                        )
                        assertFailsWith<ProviderFailure> {
                            client.resolve("AM585848")
                        }.let { assertFalse(it.transient, "a DRM channel is not listed") }
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
