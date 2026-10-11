package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class HdhrDevicesTest {
    private val token = "secret".repeat(8)

    @Test fun `each extra port is another device carrying a disjoint share of the lineup`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-hdhr-devices-")
        Fixture().use { fixture ->
            val feeds = listOf(fixture.feed, fixture.tsFeed, fixture.altFeed, fixture.twinFeed)
            val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(feeds))
            val store = openStore(dir)
            val ports = List(2) { ServerSocket(0).use { it.localPort } }
            val hdhr = HdhrConfig(deviceId = "ABCDEF01", friendlyName = "Den TV", extraPorts = listOf(ports[1]))
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", ports[0], dir, sources, token, allowLoopbackUpstreams = true, hdhr = hdhr), MutableClock())
            val server = embeddedServer(Netty, configure = { ports.forEach { p -> connector { host = "127.0.0.1"; port = p } } }) {
                backendModule(store, MutableClock(), runtime, token, hdhr = hdhr)
            }
            try {
                feeds.forEach { runtime.feeds.refresh(it) }; runtime.adoptAll()
                server.start(wait = false)
                val http = HttpClient.newHttpClient()
                fun get(port: Int, path: String): JsonElement =
                    Json.parseToJsonElement(http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).build(), HttpResponse.BodyHandlers.ofString()).body())

                val devices = ports.map { get(it, "/discover.json").jsonObject }
                assertEquals(listOf("Den TV", "Den TV 2"), devices.map { it["FriendlyName"]!!.jsonPrimitive.content })
                assertEquals("ABCDEF01", devices[0]["DeviceID"]!!.jsonPrimitive.content)
                assertNotEquals(devices[0]["DeviceID"], devices[1]["DeviceID"])
                assertEquals("http://127.0.0.1:${ports[1]}/lineup.json", devices[1]["LineupURL"]!!.jsonPrimitive.content)

                val shares = ports.map { port -> get(port, "/lineup.json").jsonArray.map { it.jsonObject["GuideNumber"]!!.jsonPrimitive.content } }
                val all = runtime.lineup.entries().filter { it.enabled }.map { it.number }.distinct()
                assertEquals(all.sorted(), shares.flatten().sorted(), "together the devices carry the whole lineup")
                assertTrue(shares[0].intersect(shares[1].toSet()).isEmpty(), "no channel is on both devices")
                assertEquals(shares, ports.map { port -> get(port, "/lineup.json").jsonArray.map { it.jsonObject["GuideNumber"]!!.jsonPrimitive.content } }, "the split is stable")
            } finally {
                server.stop(100, 2000)
                runtime.close(); store.close(); dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `with a policy each device holds a range in lineup order and the overflow goes on the last`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-hdhr-ranges-")
        Fixture().use { fixture ->
            val feeds = listOf(fixture.feed, fixture.tsFeed, fixture.twinFeed)
            val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(feeds))
            val policyFile = dir.resolve("policy.json"); Files.writeString(policyFile, """{"providers":[{"id":"fixture"},{"id":"ts"},{"id":"twin"}],"health":{"requireVerified":false}}""")
            val store = openStore(dir)
            val ports = List(2) { ServerSocket(0).use { it.localPort } }
            val hdhr = HdhrConfig(deviceId = "ABCDEF01", extraPorts = listOf(ports[1]), deviceSize = 1)
            val config = ServerConfig("127.0.0.1", ports[0], dir, sources, token, allowLoopbackUpstreams = true, hdhr = hdhr, policyFile = policyFile)
            val runtime = LiveRuntime.create(store, config, MutableClock(), policyDebounceMillis = 3_600_000, probes = false)
            val server = embeddedServer(Netty, configure = { ports.forEach { p -> connector { host = "127.0.0.1"; port = p } } }) {
                backendModule(store, MutableClock(), runtime, token, hdhr = hdhr)
            }
            try {
                feeds.forEach { runtime.feeds.refresh(it) }
                runtime.policy!!.apply("test")
                assertEquals(listOf("5", "6", "12"), runtime.lineup.entries().map { it.number })
                server.start(wait = false)
                val http = HttpClient.newHttpClient()
                fun numbers(port: Int): List<String> =
                    Json.parseToJsonElement(http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/lineup.json")).build(), HttpResponse.BodyHandlers.ofString()).body())
                        .jsonArray.map { it.jsonObject["GuideNumber"]!!.jsonPrimitive.content }
                assertEquals(listOf(listOf("5"), listOf("6", "12")), ports.map(::numbers))
                assertTrue(
                    "hdhr: 3 enabled stations exceed the 2 channels the HDHomeRun devices hold; add ports to SOFA_HDHR_EXTRA_PORTS" in runtime.policy!!.findings().findings,
                )
            } finally {
                server.stop(100, 2000)
                runtime.close(); store.close(); dir.toFile().deleteRecursively()
            }
        }
    }
}
