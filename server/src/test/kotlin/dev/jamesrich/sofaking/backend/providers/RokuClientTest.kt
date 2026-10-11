package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveProviderState
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class RokuClientTest {
    private val liveNow = "f9c0d48b540a55dcb98b04d2cc8abf25"
    private val teamUsa = "257afd66cee15a1ca2125fc377e87cc2"

    private fun ProviderFixture.roku() {
        route("/Roku/.channels.json.gz") { Canned(200, ProviderFixture.resourceBytes("roku-channels.json.gz"), "application/octet-stream") }
        route("/") { exchange ->
            if (exchange.requestURI.path != "/") Canned("not found", 404, "text/plain")
            else {
                exchange.responseHeaders.add("Set-Cookie", "_csrf=fixture-cookie; Path=/; HttpOnly")
                exchange.responseHeaders.add("Set-Cookie", "_uc=one; Path=/")
                Canned("<html></html>", contentType = "text/html")
            }
        }
        route("/api/v1/csrf") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "_uc=two; Path=/")
            Canned("""{"csrf":"fixture-csrf"}""")
        }
        fun guarded(answer: () -> Canned) = { exchange: com.sun.net.httpserver.HttpExchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            if (exchange.requestHeaders.getFirst("csrf-token") != "fixture-csrf" || "_csrf=fixture-cookie" !in cookie) Canned("Forbidden", 403, "text/plain") else answer()
        }
        route("/api/v2/homescreen/content/$base/content/$liveNow", guarded { Canned("""{"viewOptions":[{"playId":"s-fox.Zml4dHVyZQ==","providerId":"rokuavod"}]}""") })
        route("/api/v2/homescreen/content/$base/content/$teamUsa", guarded { Canned("""{"viewOptions":[{"playId":"s-locked.Zml4dHVyZQ=="}]}""") })
        route("/api/v3/playback", guarded {
            val request = Json.parseToJsonElement(lastBody["/api/v3/playback"]!!).jsonObject
            if (request["rokuId"]!!.jsonPrimitive.content == teamUsa) Canned("""{"url":"$base/osm/locked.m3u8","drm":{"widevine":{"licenseServer":"$base/wv"}}}""")
            else Canned("""{"url":"$base/osm/v1/hls/master/fixture/index.m3u8?traceId=fixture","mediaFormat":"m3u"}""")
        })
    }

    private fun options(fixture: ProviderFixture) = mapOf(
        "channelsUrl" to "${fixture.base}/Roku/.channels.json.gz", "webBase" to fixture.base, "contentBase" to "${fixture.base}/content/",
    )

    @Test fun `lists the mirror's clear channels by number with their first group`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-roku-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.roku()
            val client = RokuClient(ProviderFixture.context("roku", options(fixture), http, store, MutableClock()))
            val channels = client.channels()
            assertEquals(listOf(liveNow, "5f9c59c52f0ad475d90a33fdfd14d651", teamUsa), channels.map { it.id })
            assertEquals("LiveNOW from FOX", channels[0].name); assertEquals("103", channels[0].number); assertEquals("News", channels[0].group)
            assertEquals("Olympics", channels[2].group)
            assertTrue(channels[0].logo!!.startsWith("https://images.sr.roku.com/"))
            assertEquals(0, fixture.hits("/api/v1/csrf"), "listing needs no Roku session")
        } } } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `resolve boots a web session, looks up the playId once and refuses a DRM answer`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sofa-roku-")
        try { ProviderFixture().use { fixture -> ProviderFixture.httpClient().use { http -> openStore(dir).use { store ->
            fixture.roku()
            val clock = MutableClock()
            store.saveProviderState("roku", "session", "_csrf=stale\nstale-csrf", clock.now.plusSeconds(600), clock.now)
            val client = RokuClient(ProviderFixture.context("roku", options(fixture), http, store, clock))
            val resolved = client.resolve(liveNow)
            assertEquals("${fixture.base}/osm/v1/hls/master/fixture/index.m3u8?traceId=fixture", resolved.url)
            assertEquals(clock.now.plus(Duration.ofHours(5)), resolved.expiresAt)
            assertEquals(1, fixture.hits("/api/v1/csrf"), "the stale session was refused once and replaced")
            assertEquals("_csrf=fixture-cookie; _uc=two", fixture.lastHeaders["/api/v3/playback"]!!["cookie"], "the later cookie of a name wins")
            val request = Json.parseToJsonElement(fixture.lastBody["/api/v3/playback"]!!).jsonObject
            assertEquals("s-fox.Zml4dHVyZQ==", request["playId"]!!.jsonPrimitive.content)
            assertEquals("m3u", request["mediaFormat"]!!.jsonPrimitive.content)
            client.resolve(liveNow)
            assertEquals(2, fixture.hits("/api/v2/homescreen/content/${fixture.base}/content/$liveNow"), "refused once with the stale session, then never again")
            assertEquals(1, fixture.hits("/api/v1/csrf"), "the session is reused")
            assertFailsWith<ProviderFailure> { client.resolve(teamUsa) }.let { assertFalse(it.transient, "a DRM answer is the channel's fault") }
            assertFailsWith<ProviderFailure> { client.resolve("483af83e821059b8b3f0825ae2d43e31") }.let { assertFalse(it.transient) }
        } } } } finally { dir.toFile().deleteRecursively() }
    }
}
