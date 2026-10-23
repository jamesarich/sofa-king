package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.HdhrAccessStatus
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.upstream.readHead
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.content
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.path
import io.ktor.server.routing.application
import io.ktor.server.routing.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.header
import io.ktor.server.routing.patch
import io.ktor.server.routing.path
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                    route("/lan") { hdhrRoutes(runtime, HdhrConfig(), store, HdhrAccess(listOf("10.0.0.0/8"), false, store, MutableClock())) }
                    route("/loop") { hdhrRoutes(runtime, HdhrConfig(), store, HdhrAccess(listOf("127.0.0.1", "::1"), false, store, MutableClock())) }
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
            assertEquals(HttpStatusCode.Forbidden, client.get("/open/discover.json") { header(HttpHeaders.XForwardedFor, "203.0.113.5") }.status, "open on loopback, but not through a proxy")
            assertEquals(HttpStatusCode.NotFound, client.get("/discover.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/lineup.json").status)
        } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun `a closed bind refuses and remembers the address, and a portal allow admits it at once and survives a restart`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-hdhr-access-")
        val store = openStore(dir)
        try {
            val clock = MutableClock()
            val access = HdhrAccess(emptyList(), openWhenEmpty = false, store, clock)
            access.load()
            assertFalse(access.open)
            assertFalse(access.admits("192.168.1.20", "/discover.json"))
            clock.now = clock.now.plusSeconds(5)
            assertFalse(access.admits("172.17.0.3", "/lineup.json"))
            assertEquals(listOf("172.17.0.3", "192.168.1.20"), access.refusals().map { it.address }, "newest first")
            assertEquals("/lineup.json", access.refusals().first().path)

            access.allow("172.17.0.0/16")
            assertTrue(access.admits("172.17.0.3", "/discover.json"))
            assertEquals(listOf("192.168.1.20"), access.refusals().map { it.address }, "an allowed address leaves the refused list")
            assertFailsWith<IllegalArgumentException> { access.allow("plex.lan") }
            assertEquals(listOf("172.17.0.0/16"), access.allowed, "a bad entry changes nothing")

            val restarted = HdhrAccess(listOf("192.168.1.20"), openWhenEmpty = false, store, clock)
            restarted.load()
            assertEquals(listOf("172.17.0.0/16"), restarted.allowed)
            assertTrue(restarted.admits("192.168.1.20", "/discover.json") && restarted.admits("172.17.9.9", "/discover.json"))
            restarted.remove("172.17.0.0/16")
            assertFalse(restarted.admits("172.17.0.3", "/discover.json"))
            assertTrue(restarted.admits("192.168.1.20", "/discover.json"), "SOFA_HDHR_ALLOW stays")

            val loopback = HdhrAccess(emptyList(), openWhenEmpty = true, store, clock)
            assertTrue(loopback.open && loopback.admits("192.168.1.20", "/discover.json"))
        } finally { store.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun `the admin API lists who may use the tuners and replaces the portal's list`() = testApplication {
        val dir = Files.createTempDirectory("sofa-hdhr-access-api-")
        val store = openStore(dir)
        val sources = dir.resolve("sources.json"); Files.writeString(sources, "[]")
        val runtime = LiveRuntime.create(store, ServerConfig("0.0.0.0", 8080, dir, sources, token), MutableClock(), probes = false)
        try {
            application { backendModule(store, MutableClock(), runtime, token) }
            assertEquals(HttpStatusCode.Forbidden, client.get("/discover.json").status, "a LAN bind with no address admits nobody")
            val before = Json.decodeFromString<HdhrAccessStatus>(client.get("/api/v1/admin/hdhr/access") { bearerAuth(token) }.bodyAsText())
            assertFalse(before.open)
            assertEquals(listOf("/discover.json"), before.refused.map { it.path })
            val bad = client.put("/api/v1/admin/hdhr/access") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"allowed":["plex.lan"]}""") }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            val saved = client.put("/api/v1/admin/hdhr/access") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"allowed":["127.0.0.1"]}""") }
            assertEquals(HttpStatusCode.OK, saved.status)
            val after = Json.decodeFromString<HdhrAccessStatus>(saved.bodyAsText())
            assertEquals(listOf("127.0.0.1"), after.allowed)
            assertEquals(emptyList(), after.refused)
            assertEquals(HttpStatusCode.OK, client.get("/discover.json").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/hdhr/access").status)
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
