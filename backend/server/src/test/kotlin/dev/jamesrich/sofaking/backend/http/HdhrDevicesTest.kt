package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.FeedConfig
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HdhrDevicesTest {
    private val token = "secret".repeat(8)
    private val threeProviders =
        """{"providers":[{"id":"fixture"},{"id":"ts"},{"id":"twin"}],"health":{"requireVerified":false}}"""

    @Test fun `each extra port is another device with its own name and ID`() = twoDevices(
        feeds = { listOf(it.feed, it.tsFeed, it.altFeed, it.twinFeed) },
        hdhr = { HdhrConfig(deviceId = "ABCDEF01", friendlyName = "Den TV", extraPorts = listOf(it)) },
    ) {
        val devices = ports.map { get(it, "/discover.json").jsonObject }
        assertEquals(listOf("Den TV", "Den TV 2"), devices.map { it["FriendlyName"]!!.jsonPrimitive.content })
        assertEquals("ABCDEF01", devices[0]["DeviceID"]!!.jsonPrimitive.content)
        assertNotEquals(devices[0]["DeviceID"], devices[1]["DeviceID"])
        assertEquals("http://127.0.0.1:${ports[1]}/lineup.json", devices[1]["LineupURL"]!!.jsonPrimitive.content)
    }

    @Test fun `the devices carry disjoint shares of the lineup that hold from request to request`() = twoDevices(
        feeds = { listOf(it.feed, it.tsFeed, it.altFeed, it.twinFeed) },
        hdhr = { HdhrConfig(deviceId = "ABCDEF01", friendlyName = "Den TV", extraPorts = listOf(it)) },
    ) {
        val shares = ports.map(::numbers)
        val all = runtime.lineup.entries().filter { it.enabled }.map { it.number }.distinct()
        assertEquals(all.sorted(), shares.flatten().sorted(), "together the devices carry the whole lineup")
        assertTrue(shares[0].intersect(shares[1].toSet()).isEmpty(), "no channel is on both devices")
        assertEquals(shares, ports.map(::numbers), "the split is stable")
    }

    @Test fun `with a policy each device holds a range in lineup order and the last takes the overflow`() = twoDevices(
        feeds = { listOf(it.feed, it.tsFeed, it.twinFeed) },
        hdhr = { HdhrConfig(deviceId = "ABCDEF01", extraPorts = listOf(it), deviceSize = 1) },
        policy = threeProviders,
    ) {
        val applied = runtime.policy!!.apply("test")
        assertEquals(3, applied.enabledStations)
        assertEquals(3, applied.devicesNeeded)
        assertEquals(2, applied.devicesConfigured)
        assertEquals(listOf("5", "6", "12"), runtime.lineup.entries().map { it.number })
        assertEquals(listOf(listOf("5"), listOf("6", "12")), ports.map(::numbers))
    }

    @Test fun `a policy needing more devices than there are ports says so in its preview and findings`() = twoDevices(
        feeds = { listOf(it.feed, it.tsFeed, it.twinFeed) },
        hdhr = { HdhrConfig(deviceId = "ABCDEF01", extraPorts = listOf(it), deviceSize = 1) },
        policy = threeProviders,
    ) {
        val policy = runtime.policy!!
        policy.apply("test")
        val preview = policy.preview()
        assertEquals(3, preview.devicesNeeded)
        assertEquals(2, preview.devicesConfigured)
        assertTrue(
            "hdhr: 3 enabled stations need 3 devices of 1 channels, 2 configured; add ports to " +
                "SOFA_HDHR_EXTRA_PORTS" in policy.findings().findings,
        )
    }

    /** A running server on two [ports], the second an extra HDHomeRun device. */
    private class TwoDevices(val runtime: LiveRuntime, val ports: List<Int>) {
        private val http = HttpClient.newHttpClient()

        fun get(port: Int, path: String): JsonElement = Json.parseToJsonElement(
            http.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body(),
        )

        /** The guide numbers of the device on [port]. */
        fun numbers(port: Int): List<String> =
            get(port, "/lineup.json").jsonArray.map { it.jsonObject["GuideNumber"]!!.jsonPrimitive.content }
    }

    /**
     * Runs [block] against a server over the fixture's [feeds] on two ports, the second given to [hdhr] as an extra
     * device. Without a [policy] every feed is adopted; with one it is left for the test to apply.
     */
    private fun twoDevices(
        feeds: (Fixture) -> List<FeedConfig>,
        hdhr: (extraPort: Int) -> HdhrConfig,
        policy: String? = null,
        block: suspend TwoDevices.() -> Unit,
    ) = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-hdhr-devices-")
        Fixture().use { fixture ->
            val configs = feeds(fixture)
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(configs))
            val policyFile = policy?.let { Files.writeString(dir.resolve("policy.json"), it) }
            val store = openStore(dir)
            val ports = List(2) { ServerSocket(0).use { it.localPort } }
            val devices = hdhr(ports[1])
            val config = ServerConfig(
                "127.0.0.1",
                ports[0],
                dir,
                sources,
                token,
                allowLoopbackUpstreams = true,
                hdhr = devices,
                policyFile = policyFile,
            )
            val runtime =
                LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000, probes = false)
            val server = embeddedServer(Netty, configure = {
                ports.forEach { p ->
                    connector {
                        host = "127.0.0.1"
                        port = p
                    }
                }
            }) {
                backendModule(store, MutableClock(), runtime, token, hdhr = devices)
            }
            try {
                configs.forEach { runtime.feeds.refresh(it) }
                if (policy == null) runtime.adoptAll()
                server.start(wait = false)
                TwoDevices(runtime, ports).block()
            } finally {
                server.stop(100, 2000)
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
}
