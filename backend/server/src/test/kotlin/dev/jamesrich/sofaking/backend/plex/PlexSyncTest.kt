package dev.jamesrich.sofaking.backend.plex

import dev.jamesrich.sofaking.backend.PlexConfig
import dev.jamesrich.sofaking.backend.api.PlexDeviceStatus
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexSyncTest {
    private val token = "plex-Secret-Token-42"
    private val ours =
        listOf(HdhrDevice(0, "ABCDEF01", 8080), HdhrDevice(1, "12345678", 8081), HdhrDevice(2, "0BADF00D", 8082))

    private fun uuid(id: String) = "device://tv.plex.grabbers.hdhomerun/$id"

    /**
     * A Plex server: DVR 10 holds two of our devices and a foreign one, DVR 11 only a foreign one. It answers on
     * [answerOn] when one is given, so a test dispatcher runs the requests in virtual time.
     */
    private inner class FakePlex(answerOn: CoroutineDispatcher? = null) {
        val requests = ConcurrentLinkedQueue<HttpRequestData>()
        var dvrs = """{"MediaContainer":{"size":2,"Dvr":[
            {"key":"10","lineup":"lineup://tv.plex.providers.epg.xmltv/guide#Sofa","Device":[
              {"key":"20","uuid":"${uuid("ABCDEF01")}","uri":"http://192.168.1.3:8080","title":null},
              {"key":"21","uuid":"${uuid("FFFF0000")}","uri":"http://192.168.1.9"},
              {"key":"22","uuid":"${uuid("12345678")}","uri":"http://192.168.1.3:8081"}]},
            {"key":"11","lineup":"lineup://tv.plex.providers.epg.cloud/us","Device":{"key":"23","uuid":"${uuid(
            "FFFF0001",
        )}"}}]}}"""
        val channelMaps = mutableMapOf(
            uuid("ABCDEF01") to
                mappings(
                    listOf("5" to "five", "6" to "six"),
                ) + listOf("""{"deviceIdentifier":"7","channelKey":null}"""),
            uuid("12345678") to mappings(listOf("100.1" to "hundred")),
            uuid("0BADF00D") to mappings(listOf("300" to "three hundred")),
        )
        var added = """{"MediaContainer":{"Device":[{"key":"30","uuid":"${uuid("0BADF00D")}","title":null}]}}"""
        var handler: (
            suspend MockRequestHandleScope.(
                HttpRequestData,
            ) -> io.ktor.client.request.HttpResponseData?
        )? = null

        fun mappings(pairs: List<Pair<String, String>>): List<String> = pairs.map { (number, name) ->
            """{"deviceIdentifier":"$number","lineupIdentifier":"$name-id","channelKey":"$name-key","favorite":false}"""
        }

        private fun json(body: String) = headersOf(HttpHeaders.ContentType, "application/json")

        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = answerOn
                addHandler { request ->
                    requests += request
                    handler?.invoke(this, request)?.let { return@addHandler it }
                    val path = request.url.encodedPath
                    when {
                        request.method == HttpMethod.Get && path == "/livetv/dvrs" -> respond(
                            dvrs,
                            headers = json(dvrs),
                        )

                        request.method == HttpMethod.Get && path == "/livetv/epg/channelmap" -> {
                            val body = """{"MediaContainer":{"ChannelMapping":[${channelMaps[request.url.parameters["device"]].orEmpty().joinToString(
                                ",",
                            )}]}}"""
                            respond(body, headers = json(body))
                        }

                        request.method == HttpMethod.Post && path == "/media/grabbers/devices" -> respond(
                            added,
                            headers = json(added),
                        )

                        else -> respond("", HttpStatusCode.OK)
                    }
                }
            },
        )

        fun sync(
            tunerBase: String? = null,
            debounceMillis: Long = 120_000,
            initial: PlexStatus? = null,
            persist: suspend (PlexStatus) -> Unit = {},
        ) = PlexSync(
            PlexConfig("http://plex.test:32400", token, tunerBase = tunerBase),
            HttpClient(engine) {
                followRedirects = false
                install(HttpTimeout)
            },
            MutableClock(),
            { ours },
            debounceMillis,
            intervalMillis = 3_600_000,
            initial = initial,
            persist = persist,
        )

        fun calls(method: HttpMethod, path: String) = requests.filter {
            it.method == method && it.url.encodedPath == path
        }
    }

    private fun assertTokenInHeaderOnly(plex: FakePlex) {
        assertTrue(plex.requests.isNotEmpty())
        for (request in plex.requests) {
            assertEquals(token, request.headers["X-Plex-Token"], request.url.toString())
            assertFalse(token in request.url.toString(), "the token is never in a URL")
        }
    }

    @Test fun `maps only this server's devices, with the proposed map enabled, and reloads each DVR's guide once`() =
        runBlocking<Unit> {
            val plex = FakePlex()
            val status = plex.sync().sync("test")

            assertNull(status.lastError)
            assertEquals(
                listOf(uuid("ABCDEF01"), uuid("12345678")),
                plex.calls(HttpMethod.Get, "/livetv/epg/channelmap").map {
                    it.url.parameters["device"]
                },
            )
            assertTrue(
                plex.calls(HttpMethod.Get, "/livetv/epg/channelmap").all {
                    it.url.parameters["lineup"] ==
                        "lineup://tv.plex.providers.epg.xmltv/guide#Sofa"
                },
            )
            assertEquals(
                emptyList(),
                plex.requests.filter {
                    "/21/" in it.url.encodedPath || "/23/" in it.url.encodedPath
                },
                "foreign devices are left alone",
            )

            val first = plex.calls(HttpMethod.Put, "/media/grabbers/devices/20/channelmap").single().url.parameters
            assertEquals("5,6", first["channelsEnabled"])
            assertEquals("five-key", first["channelMappingByKey[5]"])
            assertEquals("five-id", first["channelMapping[5]"])
            assertEquals("six-key", first["channelMappingByKey[6]"])
            assertEquals("six-id", first["channelMapping[6]"])
            assertNull(first["channelMapping[7]"], "a channel Plex proposes nothing for stays out")
            val second = plex.calls(HttpMethod.Put, "/media/grabbers/devices/22/channelmap").single().url.parameters
            assertEquals("100.1", second["channelsEnabled"])
            assertEquals("hundred-key", second["channelMappingByKey[100.1]"])

            assertEquals(1, plex.calls(HttpMethod.Post, "/livetv/dvrs/10/reloadGuide").size)
            assertEquals(0, plex.calls(HttpMethod.Post, "/livetv/dvrs/11/reloadGuide").size)
            assertTokenInHeaderOnly(plex)

            assertEquals(listOf("10"), status.dvrs)
            assertEquals(listOf("ABCDEF01" to 2, "12345678" to 1), status.devices.map { it.deviceId to it.mapped })
            assertEquals(listOf("20", "22"), status.devices.map { it.plexKey })
            assertEquals(listOf("0BADF00D"), status.missingDevices)
            assertEquals(listOf("plex: device 0BADF00D (device 2) is not added to Plex"), status.findings)
            assertEquals("test", status.lastCause)
            assertNotNull(status.lastSyncAt)
        }

    @Test fun `with a tuner base a missing device is added to the first DVR holding ours, then mapped`() = runBlocking<Unit> {
        val plex = FakePlex()
        val status = plex.sync(tunerBase = "http://192.168.1.3").sync("test")

        assertNull(status.lastError)
        assertEquals(
            "http://192.168.1.3:8082",
            plex.calls(HttpMethod.Post, "/media/grabbers/devices").single().url.parameters["uri"],
        )
        assertEquals(1, plex.calls(HttpMethod.Put, "/livetv/dvrs/10/devices/30").size)
        assertEquals(
            "300",
            plex.calls(
                HttpMethod.Put,
                "/media/grabbers/devices/30/channelmap",
            ).single().url.parameters["channelsEnabled"],
        )
        assertEquals(1, plex.calls(HttpMethod.Post, "/livetv/dvrs/10/reloadGuide").size)
        assertEquals(emptyList(), status.missingDevices)
        assertEquals(emptyList(), status.findings)
        assertEquals(
            PlexDeviceStatusView("0BADF00D", "30", "10", 1, true),
            status.devices.single {
                it.device == 2
            }.let { PlexDeviceStatusView(it.deviceId, it.plexKey, it.dvr, it.mapped, it.added) },
        )
        assertTokenInHeaderOnly(plex)
    }

    @Test fun `the stored status shows until a sync replaces and stores it`() = runBlocking<Unit> {
        val plex = FakePlex()
        val stored =
            PlexStatus(
                1,
                "2026-10-01T00:00:00Z",
                "interval",
                null,
                listOf("10"),
                listOf(PlexDeviceStatus("ABCDEF01", 0, "20", "10", 7)),
            )
        val persisted = ConcurrentLinkedQueue<PlexStatus>()
        val sync = plex.sync(initial = stored, persist = { persisted += it })

        assertEquals("2026-10-01T00:00:00Z", sync.status().lastSyncAt)
        assertEquals(7, sync.status().devices.single().mapped)
        assertEquals(
            PlexConfig("http://plex.test:32400", token).syncMinutes,
            sync.status().syncMinutes,
            "the interval comes from the current settings",
        )
        assertEquals(emptyList(), persisted.toList(), "nothing is stored before a sync")

        val next = sync.sync("test")
        assertEquals(listOf(next), persisted.toList())
        assertEquals(next, sync.status())
        assertEquals("test", sync.status().lastCause)
    }

    @Test fun `a status that cannot be stored still counts as the sync`() = runBlocking<Unit> {
        val plex = FakePlex()
        val sync = plex.sync(persist = { throw java.io.IOException("disk full") })
        val next = sync.sync("test")
        assertNull(next.lastError)
        assertEquals(next, sync.status())
    }

    private data class PlexDeviceStatusView(
        val id: String,
        val key: String,
        val dvr: String,
        val mapped: Int,
        val added: Boolean,
    )

    @Test fun `a device Plex adds under another DeviceID never joins the DVR`() = runBlocking<Unit> {
        val plex = FakePlex()
        plex.added = """{"MediaContainer":{"Device":[{"key":"31","uuid":"${uuid("DEADBEEF")}"}]}}"""
        val status = plex.sync(tunerBase = "http://192.168.1.3").sync("test")

        assertEquals(0, plex.requests.count { it.url.encodedPath.startsWith("/livetv/dvrs/10/devices") })
        assertTrue(
            status.lastError!!.contains("device 0BADF00D: port 8082 answers as DEADBEEF, not 0BADF00D"),
            status.lastError,
        )
        assertEquals(listOf("0BADF00D"), status.missingDevices)
        assertEquals(2, status.devices.size, "the devices Plex has are still mapped")
    }

    @Test fun `a channel map too long for Plex to save is reported instead of sent`() = runBlocking<Unit> {
        val plex = FakePlex()
        plex.channelMaps[uuid("ABCDEF01")] =
            plex.mappings((1..400).map { "$it" to "a-long-lineup-identifier-for-channel-$it" })
        val status = plex.sync().sync("test")

        assertEquals(0, plex.calls(HttpMethod.Put, "/media/grabbers/devices/20/channelmap").size)
        assertEquals(1, plex.calls(HttpMethod.Put, "/media/grabbers/devices/22/channelmap").size)
        assertTrue(
            "plex: device ABCDEF01 has 400 channels, too many for Plex to save; lower SOFA_HDHR_DEVICE_SIZE or add ports" in
                status.findings,
        )
        assertEquals(0, status.devices.single { it.deviceId == "ABCDEF01" }.mapped)
        assertEquals(1, plex.calls(HttpMethod.Post, "/livetv/dvrs/10/reloadGuide").size)
    }

    @Test fun `a device with an empty channel map is skipped and its DVR is not reloaded for it`() = runBlocking<Unit> {
        val plex = FakePlex()
        plex.channelMaps[uuid("ABCDEF01")] = emptyList()
        plex.channelMaps[uuid("12345678")] = emptyList()
        val status = plex.sync().sync("test")

        assertEquals(0, plex.requests.count { it.method == HttpMethod.Put })
        assertEquals(0, plex.calls(HttpMethod.Post, "/livetv/dvrs/10/reloadGuide").size)
        assertEquals(emptyList(), status.dvrs)
    }

    @Test fun `errors are recorded without the token, and a redirect is not followed`() = runBlocking<Unit> {
        val plex = FakePlex()
        plex.dvrs = """{"MediaContainer": $token oops"""
        val broken = plex.sync().sync("test")
        assertNotNull(broken.lastError)
        assertFalse(token in broken.lastError, broken.lastError)

        val redirected = FakePlex()
        redirected.handler = { request ->
            if (request.url.encodedPath == "/livetv/dvrs") {
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "http://elsewhere.test/livetv/dvrs"),
                )
            } else {
                null
            }
        }
        val status = redirected.sync().sync("test")
        assertEquals("GET /livetv/dvrs answered 302", status.lastError)
        assertEquals(listOf("plex: sync failed: GET /livetv/dvrs answered 302"), status.findings)
        assertEquals(1, redirected.requests.size)
        assertTrue(redirected.requests.none { it.url.host == "elsewhere.test" })

        val refused = FakePlex()
        refused.handler =
            { request ->
                if (request.url.encodedPath == "/livetv/dvrs/10/reloadGuide") {
                    respond("", HttpStatusCode.Unauthorized)
                } else {
                    null
                }
            }
        val partial = refused.sync().sync("test")
        assertEquals("DVR 10: POST /livetv/dvrs/10/reloadGuide answered 401", partial.lastError)
        assertEquals(2, partial.devices.size)
    }

    // The loop and the fake Plex run on the test scheduler, so the debounce passes in virtual time and nothing can
    // happen between two checks unless the scheduler runs it.
    @OptIn(ExperimentalCoroutinesApi::class) // the test scheduler's time controls
    @Test
    fun `lineup changes are debounced into one sync`() = runTest {
        val plex = FakePlex(StandardTestDispatcher(testScheduler))
        val sync = plex.sync(debounceMillis = 300)
        sync.start(backgroundScope)
        sync.requestSync("policy")
        sync.requestSync("policy")
        sync.requestSync("startup")
        advanceTimeBy(299)
        assertEquals(0, plex.calls(HttpMethod.Get, "/livetv/dvrs").size, "nothing runs inside the debounce")
        advanceTimeBy(1)
        runCurrent()
        assertNotNull(sync.status().lastSyncAt)
        advanceTimeBy(10 * 300)
        assertEquals(1, plex.calls(HttpMethod.Get, "/livetv/dvrs").size)
        assertTrue(sync.status().lastCause in setOf("policy", "startup"), "a coalesced request keeps one of its causes")
    }
}
