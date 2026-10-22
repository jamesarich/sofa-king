package dev.jamesrich.sofaking.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SofaKingApiTest {
    /** For URL resolution, which sends nothing. */
    private val offline = MockEngine { error("no request expected") }

    @Test
    fun `the catalog request carries the bearer token and decodes the channels`() = runTest {
        val http = FakeHttp(this) {
            json("""{"revision":"r1","channels":[{"id":"c1","displayNumber":"5","name":"Five","playbackEligible":true,"extra":1}]}""", etag = "\"r1\"")
        }

        val result = http.api().catalog(etag = null) as CatalogResult.Changed

        val request = http.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/channels", request.path)
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertNull(request.headers["If-None-Match"])
        assertEquals("\"r1\"", result.etag)
        assertEquals(listOf(CatalogChannelDto("c1", "5", "Five", playbackEligible = true)), result.catalog.channels)
    }

    @Test
    fun `a matching ETag is sent back and answers not modified`() = runTest {
        val http = FakeHttp(this) { respond("", HttpStatusCode.NotModified) }

        val result = http.api().catalog(etag = "\"r1\"")

        assertEquals("\"r1\"", http.requests.single().headers["If-None-Match"])
        assertEquals(CatalogResult.NotModified, result)
    }

    @Test
    fun `an empty token sends no authorization header`() = runTest {
        val http = FakeHttp(this) { json("""{"revision":"r1","channels":[]}""") }

        http.api(token = "").catalog(etag = null)

        assertNull(http.requests.single().headers["Authorization"])
    }

    @Test
    fun `a base address with a path keeps it in front of the API`() = runTest {
        val http = FakeHttp(this) { json("""{"revision":"r1","channels":[]}""") }

        http.api(base = "http://sofa.test/tv/").catalog(etag = null)

        assertEquals("/tv/api/v1/channels", http.requests.single().path)
    }

    @Test
    fun `an API error becomes an exception with its code`() = runTest {
        val http = FakeHttp(this) { json("""{"code":"AUTH_REQUIRED","message":"Bearer token required","retryable":false}""", status = 401) }

        val e = assertFailsWith<SofaKingException> { http.api(token = "wrong").session("s1") }

        assertEquals(401, e.status)
        assertEquals("AUTH_REQUIRED", e.code)
        assertEquals("Bearer token required", e.message)
    }

    @Test
    fun `a non-JSON error answer is reported by its status`() = runTest {
        val http = FakeHttp(this) { respond("bad gateway", HttpStatusCode.BadGateway) }

        val e = assertFailsWith<SofaKingException> { http.api().session("s1") }

        assertEquals(502, e.status)
        assertEquals("HTTP_502", e.code)
    }

    @Test
    fun `an unreadable success answer is an IOException`() = runTest {
        val http = FakeHttp(this) { json("""{"revision":""") }

        val e = assertFailsWith<IOException> { http.api().catalog(etag = null) }

        assertEquals("Unreadable answer from the server", e.message)
    }

    @Test
    fun `an engine failure is an IOException`() = runTest {
        val http = FakeHttp(this) { throw IllegalStateException("engine gone") }

        val e = assertFailsWith<IOException> { http.api().catalog(etag = null) }

        assertTrue(e !is SofaKingException)
    }

    @Test
    fun `a failed browser fetch is an IOException and an Error elsewhere stays one`() = runTest {
        val http = FakeHttp(this) { throw Error("Fail to fetch") }

        val e = assertFails { http.api().catalog(etag = null) }

        if (engineFailsWithErrors) assertTrue(e is IOException) else assertTrue(e !is IOException)
    }

    @Test
    fun `session requests encode the id as one path segment and the body as JSON`() = runTest {
        val session = """{"id":"s 1/2","channelId":"c1","intent":"watch","expiresAt":"2026-10-10T10:00:00Z","descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/sessions/s1/1/root.m3u8","epoch":1}}"""
        val http = FakeHttp(this) { request -> if (request.method == "DELETE") respond("", HttpStatusCode.NoContent) else json(session) }

        val api = http.api()
        api.updateSession("s 1/2", "watch")
        api.updateSession("s 1/2", null)
        api.releaseSession("s 1/2")

        val (promote, renew, release) = http.requests
        assertEquals("PATCH", promote.method)
        assertEquals("/api/v1/playback-sessions/s%201%2F2", promote.path)
        assertEquals("""{"intent":"watch"}""", promote.body)
        assertEquals("application/json; charset=utf-8", promote.headers["Content-Type"])
        assertEquals("{}", renew.body)
        assertEquals("DELETE", release.method)
        assertEquals("Bearer secret", release.headers["Authorization"])
    }

    @Test
    fun `media URLs resolve against the server address`() {
        val descriptor = PlaybackDescriptorDto("hls", "application/vnd.apple.mpegurl", "/media/sessions/s1/1/root.m3u8", 1)

        assertEquals("http://tv.lan:9193/media/sessions/s1/1/root.m3u8", SofaKingApi("http://tv.lan:9193/", "t", offline).mediaUrl(descriptor))
    }

    @Test
    fun `logos resolve against the server address and absolute ones pass through`() {
        val api = SofaKingApi("https://tv.example", "t", offline)

        assertEquals("https://tv.example/logos/0f3a", api.logoUrl("/logos/0f3a"))
        assertEquals("https://cdn.example/a.png", api.logoUrl("https://cdn.example/a.png"))
    }

    @Test
    fun `an unparsable logo is dropped and an unparsable media URL is an IOException`() {
        val api = SofaKingApi("https://tv.example", "t", offline)

        assertNull(api.logoUrl("http://h:port/x"))
        assertFailsWith<IOException> { api.mediaUrl(PlaybackDescriptorDto("hls", "application/vnd.apple.mpegurl", "http://h:port/x", 1)) }
    }

    @Test
    fun `HLS descriptors get the MIME type Media3 recognizes and MPEG-TS passes through`() {
        assertEquals("application/x-mpegURL", mediaMimeType(PlaybackDescriptorDto("hls", "application/vnd.apple.mpegurl", "/x.m3u8", 1)))
        assertEquals("application/x-mpegURL", mediaMimeType(PlaybackDescriptorDto("hls", "Application/X-MpegURL", "/x", 1)))
        assertEquals("video/mp2t", mediaMimeType(PlaybackDescriptorDto("mpegts", "video/mp2t", "/x.ts", 1)))
    }

    @Test
    fun `a pairing claim is posted without a token and answers the server's`() = runTest {
        val http = FakeHttp(this) { json("""{"apiToken":"issued","serverId":"srv"}""") }

        val result = http.api(token = "").pair(PairingClaimDto("123456", "c1", "Den", "android-tv"))

        val request = http.requests.single()
        assertEquals("POST /api/v1/pair {\"code\":\"123456\",\"clientId\":\"c1\",\"name\":\"Den\",\"platform\":\"android-tv\"}", request.toString())
        assertNull(request.headers["Authorization"])
        assertEquals("issued", result.apiToken)
        assertEquals("srv", result.serverId)
    }

    @Test
    fun `missing preferences read as null and saved ones come back stamped`() = runTest {
        val http = FakeHttp(this) { request ->
            if (request.method == "GET") apiError(404, "VIEWER_NOT_FOUND")
            else json("""{"hidden":["h"],"favorites":[],"recent":[],"name":"Den","platform":"desktop","updatedAt":"2026-10-16T00:00:00Z"}""")
        }

        assertNull(http.api().preferences("c 1"))
        assertEquals("/api/v1/preferences/c%201", http.requests.single().path)
        val saved = http.api().savePreferences("c1", ViewerPreferencesDto(hidden = listOf("h"), name = "Den", platform = "desktop"))
        assertEquals("PUT /api/v1/preferences/c1 {\"hidden\":[\"h\"],\"name\":\"Den\",\"platform\":\"desktop\"}", http.requests.last().toString())
        assertEquals("2026-10-16T00:00:00Z", saved.updatedAt)
    }
}
