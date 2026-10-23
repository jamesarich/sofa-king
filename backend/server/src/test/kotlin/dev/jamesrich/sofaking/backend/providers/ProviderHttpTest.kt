package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderHttpTest {
    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test fun `gzip that inflates past the cap is refused as a provider failure`() {
        val bomb = gzip(ByteArray(1024 * 1024))
        assertTrue(bomb.size < 16 * 1024, "the fixture must be small compressed")
        assertFailsWith<ProviderFailure> { ProviderHttp.gunzipped(bomb, 64 * 1024) }
    }

    @Test fun `gzip that inflates within the cap is returned whole`() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        assertContentEquals(payload, ProviderHttp.gunzipped(gzip(payload), 64 * 1024))
    }

    @Test fun `a redirect to another origin drops the credentials and keeps the other headers`() = runBlocking<Unit> {
        ProviderFixture().use { fixture ->
            val elsewhere = fixture.base.replace("127.0.0.1", "localhost")
            fixture.route("/start") { Canned(302, location = "/same") }
            fixture.route("/same") { Canned(302, location = "$elsewhere/cdn") }
            fixture.json("/cdn", """{"ok":true}""")
            val headers = mapOf("Authorization" to "Bearer secret", "Cookie" to "s=1", "User-Agent" to "web", "csrf-token" to "c")
            val http = ProviderHttp(ProviderFixture.httpClient(), UpstreamPolicy(allowLoopback = true))
            val answer = http.get("${fixture.base}/start", headers)
            assertEquals("$elsewhere/cdn", answer.url)
            assertEquals("Bearer secret", fixture.lastHeaders.getValue("/same")["authorization"], "a same-origin hop keeps the token")
            val cdn = fixture.lastHeaders.getValue("/cdn")
            assertNull(cdn["authorization"])
            assertNull(cdn["cookie"])
            assertEquals("web", cdn["user-agent"])
            assertEquals("c", cdn["csrf-token"])
        }
    }

    @Test fun `the array after a key is cut out of a page, past back-references and brackets in strings`() {
        val page = """self.push({"items":"${'$'}1"}); var state = {"items": [{"name":"a ] [ \" b"},[1,2]], "rest": {"""
        assertEquals("""[{"name":"a ] [ \" b"},[1,2]]""", jsonArrayAfter(page, "items").toString())
        assertNull(jsonArrayAfter(page, "missing"))
        assertNull(jsonArrayAfter("""{"items": [1, 2""", "items"))
    }

    @Test fun `next data reads the page state and names the page when it has none`() {
        val page = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"a":1}}</script></html>"""
        assertEquals("1", nextData(page, "Test page")["props"]["a"].toString())
        assertEquals("Test page has no __NEXT_DATA__", assertFailsWith<ProviderFailure> { nextData("<html></html>", "Test page") }.message)
    }
}
