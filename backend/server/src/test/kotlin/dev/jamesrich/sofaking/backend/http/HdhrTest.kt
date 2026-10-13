package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.contract.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.readHead
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class HdhrTest {
    private val token = "secret".repeat(8)
    private val base = "http://localhost:80"

    @Test fun `discovery, lineup and device description match what Plex reads`() = testApplication {
        val dir = Files.createTempDirectory("sofa-hdhr-")
        Fixture().use { fixture ->
            val feeds = listOf(fixture.feed, fixture.tsFeed)
            val sources = dir.resolve("sources.json"); Files.writeString(sources, Json.encodeToString(feeds))
            val store = openStore(dir)
            val hdhr = HdhrConfig(tuners = 3, deviceId = "ABCDEF01", friendlyName = "Den TV")
            val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, hdhr = hdhr), MutableClock())
            try {
                feeds.forEach { runtime.feeds.refresh(it) }; runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token, hdhr = hdhr) }
                val channels = Json.decodeFromString<Catalog>(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).channels
                val ts = channels.single { it.displayNumber == "6" }

                val discover = client.get("/discover.json")
                assertEquals(HttpStatusCode.OK, discover.status)
                assertTrue(discover.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
                val device = Json.parseToJsonElement(discover.bodyAsText()).jsonObject
                assertEquals("Den TV", device["FriendlyName"]!!.jsonPrimitive.content)
                assertEquals("ABCDEF01", device["DeviceID"]!!.jsonPrimitive.content)
                assertEquals("HDTC-2US", device["ModelNumber"]!!.jsonPrimitive.content)
                assertEquals("hdhomeruntc_atsc", device["FirmwareName"]!!.jsonPrimitive.content)
                assertEquals(base, device["BaseURL"]!!.jsonPrimitive.content)
                assertEquals("$base/lineup.json", device["LineupURL"]!!.jsonPrimitive.content)
                assertEquals(3, device["TunerCount"]!!.jsonPrimitive.int)
                assertTrue(device.containsKey("DeviceAuth") && device.containsKey("Manufacturer"))

                val lineup = Json.parseToJsonElement(client.get("/lineup.json").bodyAsText()).jsonArray.map { it.jsonObject }
                assertEquals(listOf("5", "6"), lineup.map { it["GuideNumber"]!!.jsonPrimitive.content })
                assertEquals(listOf("Station", "TS Station"), lineup.map { it["GuideName"]!!.jsonPrimitive.content })
                assertEquals(listOf("$base/auto/v5", "$base/auto/v6"), lineup.map { it["URL"]!!.jsonPrimitive.content })
                assertEquals(setOf("GuideNumber", "GuideName", "URL"), lineup.first().keys)

                assertEquals("""{"ScanInProgress":0,"ScanPossible":0,"Source":"Cable","SourceList":["Cable"]}""", client.get("/lineup_status.json").bodyAsText())
                assertEquals(HttpStatusCode.OK, client.get("/lineup.post?scan=start").status)
                assertEquals(HttpStatusCode.OK, client.post("/lineup.post?scan=start").status)

                val description = client.get("/device.xml")
                assertTrue(description.headers[HttpHeaders.ContentType]!!.startsWith("application/xml"))
                val xml = description.bodyAsText()
                assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
                for (expected in listOf("<URLBase>$base</URLBase>", "<friendlyName>Den TV</friendlyName>", "<serialNumber>ABCDEF01</serialNumber>", "<UDN>uuid:${store.serverId()}</UDN>")) {
                    assertTrue(xml.contains(expected), expected)
                }

                // A TS source streams through the fan-out under the channel number.
                val head = withTimeout(20_000) {
                    client.prepareGet("/auto/v6").execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)
                        assertEquals("video/mp2t", response.headers[HttpHeaders.ContentType])
                        response.bodyAsChannel().readHead(188 * 3)
                    }
                }
                for (packet in 0 until 3) assertEquals(0x47.toByte(), head[packet * 188])
                assertEquals(HttpStatusCode.NotFound, client.get("/auto/v99").status)

                // Plex refuses duplicate guide numbers, so the second entry with a number is left out of the lineup.
                client.patch("/api/v1/admin/lineup/${ts.id}") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"number":"5"}""") }
                assertEquals(listOf("Station"), Json.parseToJsonElement(client.get("/lineup.json").bodyAsText()).jsonArray.map { it.jsonObject["GuideName"]!!.jsonPrimitive.content })
                client.patch("/api/v1/admin/lineup/${ts.id}") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"enabled":false,"number":"6"}""") }
                assertEquals(1, Json.parseToJsonElement(client.get("/lineup.json").bodyAsText()).jsonArray.size)
                assertEquals(HttpStatusCode.NotFound, client.get("/auto/v6").status)
            } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun `the device ID derives from the server identity, the allow-list gates every route, and disabling removes them`() = testApplication {
        val dir = Files.createTempDirectory("sofa-hdhr-gates-")
        val store = openStore(dir)
        val sources = dir.resolve("sources.json"); Files.writeString(sources, "[]")
        val runtime = LiveRuntime.create(store, ServerConfig("127.0.0.1", 8080, dir, sources, token), MutableClock())
        try {
            application {
                routing {
                    route("/open") { hdhrRoutes(runtime, HdhrConfig(), store) }
                    route("/lan") { hdhrRoutes(runtime, HdhrConfig(allow = listOf("10.0.0.0/8")), store) }
                    route("/loop") { hdhrRoutes(runtime, HdhrConfig(allow = listOf("127.0.0.1", "::1")), store) }
                }
                backendModule(store, MutableClock(), runtime, token, hdhr = HdhrConfig(enabled = false))
            }
            val derived = Json.parseToJsonElement(client.get("/open/discover.json").bodyAsText()).jsonObject["DeviceID"]!!.jsonPrimitive.content
            assertTrue(derived.matches(Regex("[0-9A-F]{8}")), derived)
            assertEquals(derived, Json.parseToJsonElement(client.get("/open/discover.json").bodyAsText()).jsonObject["DeviceID"]!!.jsonPrimitive.content)
            for (path in listOf("discover.json", "lineup.json", "lineup_status.json", "device.xml", "lineup.post", "auto/v5")) {
                assertEquals(HttpStatusCode.Forbidden, client.get("/lan/$path").status, path)
            }
            assertEquals(HttpStatusCode.OK, client.get("/loop/discover.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/discover.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/lineup.json").status)
        } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun `the allow-list takes IP literals and CIDR blocks and nothing that needs DNS`() {
        val list = AddressAllowList(listOf("192.168.1.0/24", "10.1.2.3", "fd00::/8"))
        assertFalse(list.open)
        for (allowed in listOf("192.168.1.77", "192.168.1.0", "10.1.2.3", "fd12::1", "::ffff:192.168.1.5", "[fd00::1]")) assertTrue(list.allows(allowed), allowed)
        for (refused in listOf("192.168.2.1", "10.1.2.4", "::1", "localhost", "plex.lan", "")) assertFalse(list.allows(refused), refused)
        val loopback = AddressAllowList(listOf("127.0.0.1"))
        assertTrue(loopback.allows("localhost") && loopback.allows("127.0.0.1") && !loopback.allows("127.0.0.2"))
        assertTrue(AddressAllowList(emptyList()).let { it.open && it.allows("8.8.8.8") })
        for (bad in listOf("plex.lan", "192.168.1.0/33", "10.0.0.0/x", "/8", "192.168.1")) {
            assertFailsWith<IllegalArgumentException>(bad) { AddressAllowList(listOf(bad)) }
        }
    }
}
