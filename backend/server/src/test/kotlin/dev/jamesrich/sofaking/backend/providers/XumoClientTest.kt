package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.providerState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XumoClientTest {
    private val inTouch = "99951481"
    private val liveNow = "99991635"

    private fun ProviderFixture.xumo() {
        json("/v2/proxy/channels/list/10006.json", ProviderFixture.resource("xumo-channels.json"))
        json(
            "/v2/channels/channel/$inTouch/broadcast.json",
            ProviderFixture.resource("xumo-broadcast.json").replace("BASE", base),
        )
        json("/v2/channels/channel/$liveNow/broadcast.json", ProviderFixture.resource("xumo-broadcast-asset.json"))
        json("/v2/assets/asset/XM1YMKFW9XMSV6.json", ProviderFixture.resource("xumo-asset.json").replace("BASE", base))
        json("/v2/epg/10006/20261010/2.json", ProviderFixture.resource("xumo-epg.json"))
        json("/v2/epg/10006/20261010/3.json", """{"totalChannels":2,"channels":[],"assets":{}}""")
        route("/v2/epg/10006/20261011/0.json") { Canned("", 400) }
    }

    @Test fun `lists live channels without the DRM call signs`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-xumo-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.xumo()
                        val client =
                            XumoClient(
                                ProviderFixture.context(
                                    "xumo",
                                    mapOf("apiBase" to fixture.base),
                                    http,
                                    store,
                                    MutableClock(),
                                ),
                            )
                        val channels = client.channels()
                        assertEquals(
                            listOf(inTouch, liveNow),
                            channels.map {
                                it.id
                            },
                            "the -DRM call sign and the channel that is not live are left out",
                        )
                        assertEquals("In Touch + Network", channels[0].name)
                        assertEquals("2013", channels[0].number)
                        assertEquals("Faith & Family", channels[0].group)
                        assertEquals(
                            "https://image.xumo.com/v1/channels/channel/$inTouch/248x140.png?type=color_onBlack",
                            channels[0].logo,
                        )
                        val deviceId = store.providerState("xumo", ProviderContext.DEVICE_ID)!!.value
                        assertEquals(
                            "sort=hybrid&geoId=2f08a9b3&deviceId=$deviceId&ifaId=$deviceId",
                            fixture.lastQuery["/v2/proxy/channels/list/10006.json"],
                        )
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `resolve fills the stitched template, or the live asset's HLS source when there is none`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-xumo-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.xumo()
                        val clock = MutableClock()
                        val client =
                            XumoClient(
                                ProviderFixture.context("xumo", mapOf("apiBase" to fixture.base), http, store, clock),
                            )
                        val resolved = client.resolve(inTouch)
                        val deviceId = store.providerState("xumo", ProviderContext.DEVICE_ID)!!.value
                        assertEquals(
                            "${fixture.base}/xumo/10001/$inTouch/hls/playlist.m3u8?ads.xumo_channelId=99951481&ads.csid=xumo_web_ssai&ads._fw_did=$deviceId&ads._fw_is_lat=0&ads.lat=&ads._fw_us_privacy=1---",
                            resolved.url,
                        )
                        assertEquals("hour=13", fixture.lastQuery["/v2/channels/channel/$inTouch/broadcast.json"])
                        assertEquals(clock.now.plus(Duration.ofHours(1)), resolved.expiresAt)
                        assertEquals(
                            "${fixture.base}/xumo/10001/99991635/hls/playlist.m3u8?ads.xumo_channelId=99991635&ads._fw_did=$deviceId&ads.amznappId=&ads.genre=Reality%E2%80%91TV,Reality%20Shows",
                            client.resolve(liveNow).url,
                            "the space and the non-ASCII hyphen in the source's ad parameters are encoded",
                        )
                        assertFailsWith<ProviderFailure> {
                            client.resolve("99991351")
                        }.let { assertFalse(it.transient, "a DRM channel is not listed") }
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `guide reads the six-hour blocks from the current one and stops at an unpublished block`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-xumo-")
        try {
            ProviderFixture().use { fixture ->
                ProviderFixture.httpClient().use { http ->
                    openStore(dir).use { store ->
                        fixture.xumo()
                        val client =
                            XumoClient(
                                ProviderFixture.context(
                                    "xumo",
                                    mapOf("apiBase" to fixture.base),
                                    http,
                                    store,
                                    MutableClock(),
                                ),
                            )
                        assertTrue(client.guide(emptySet()).programmes.isEmpty())
                        assertEquals(
                            0,
                            fixture.hits("/v2/epg/10006/20261010/2.json"),
                            "nothing mapped, nothing fetched",
                        )
                        val guide = client.guide(setOf(inTouch))
                        assertEquals(2, guide.channels.size)
                        assertEquals(1, fixture.hits("/v2/epg/10006/20261010/2.json"))
                        assertEquals(1, fixture.hits("/v2/epg/10006/20261010/3.json"))
                        assertEquals(1, fixture.hits("/v2/epg/10006/20261011/0.json"))
                        assertTrue(fixture.lastQuery["/v2/epg/10006/20261010/2.json"]!!.endsWith("limit=50&offset=0"))
                        assertEquals(
                            listOf("Dateline NBC", "Dateline NBC"),
                            guide.programmes.map {
                                it.title
                            },
                            "only the mapped channel's rows",
                        )
                        assertEquals("2026-10-11T00:00:00Z", guide.programmes[0].startsAt.toString())
                        assertEquals(
                            listOf(
                                listOf(EpisodeNumber("dd_progid", "EP00019155.3073")),
                                listOf(EpisodeNumber("dd_progid", "EP00019155.3082")),
                            ),
                            guide.programmes.map { it.episodeNumbers },
                            "the Gracenote asset id is the programme id",
                        )
                        assertTrue(guide.programmes.all { it.categories.isEmpty() }, "an episode id names no kind")
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
