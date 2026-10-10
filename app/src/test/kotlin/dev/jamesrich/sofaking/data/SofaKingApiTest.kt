package dev.jamesrich.sofaking.data

import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SofaKingApiTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(token: String = "secret") = SofaKingApi(server.url("/").toString(), token)

    private fun json(body: String, code: Int = 200, etag: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (etag != null) addHeader("ETag", etag) }
        .body(body)
        .build()

    @Test
    fun `the catalog request carries the bearer token and decodes the channels`() = runTest {
        server.enqueue(json("""{"revision":"r1","channels":[{"id":"c1","displayNumber":"5","name":"Five","playbackEligible":true,"extra":1}]}""", etag = "\"r1\""))

        val result = api().catalog(etag = null) as CatalogResult.Changed

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/channels", request.url.encodedPath)
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertNull(request.headers["If-None-Match"])
        assertEquals("\"r1\"", result.etag)
        assertEquals(listOf(CatalogChannelDto("c1", "5", "Five", playbackEligible = true)), result.catalog.channels)
    }

    @Test
    fun `a matching ETag is sent back and answers not modified`() = runTest {
        server.enqueue(MockResponse.Builder().code(304).build())

        val result = api().catalog(etag = "\"r1\"")

        assertEquals("\"r1\"", server.takeRequest().headers["If-None-Match"])
        assertEquals(CatalogResult.NotModified, result)
    }

    @Test
    fun `an empty token sends no authorization header`() = runTest {
        server.enqueue(json("""{"revision":"r1","channels":[]}"""))

        api(token = "").catalog(etag = null)

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a base address with a path keeps it in front of the API`() = runTest {
        server.enqueue(json("""{"revision":"r1","channels":[]}"""))

        SofaKingApi(server.url("/tv/").toString(), "t").catalog(etag = null)

        assertEquals("/tv/api/v1/channels", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `an API error becomes an exception with its code`() = runTest {
        server.enqueue(json("""{"code":"AUTH_REQUIRED","message":"Bearer token required","retryable":false}""", code = 401))

        try {
            api(token = "wrong").session("s1")
            fail("expected an exception")
        } catch (e: SofaKingException) {
            assertEquals(401, e.status)
            assertEquals("AUTH_REQUIRED", e.code)
            assertEquals("Bearer token required", e.message)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun `a non-JSON error answer is reported by its status`() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("bad gateway").build())

        try {
            api().session("s1")
            fail("expected an exception")
        } catch (e: SofaKingException) {
            assertEquals(502, e.status)
            assertEquals("HTTP_502", e.code)
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `session requests encode the id as one path segment and the body as JSON`() = runTest {
        val session = """{"id":"s 1","channelId":"c1","intent":"watch","expiresAt":"2026-10-10T10:00:00Z","descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/sessions/s%201/1/root.m3u8","epoch":1}}"""
        server.enqueue(json(session))
        server.enqueue(json(session))
        server.enqueue(MockResponse.Builder().code(204).build())

        val api = api()
        api.updateSession("s 1", "watch")
        api.updateSession("s 1", null)
        api.releaseSession("s 1")

        val promote = server.takeRequest()
        assertEquals("PATCH", promote.method)
        assertEquals("/api/v1/playback-sessions/s%201", promote.url.encodedPath)
        assertEquals("""{"intent":"watch"}""", promote.body?.utf8())
        assertEquals("application/json; charset=utf-8", promote.headers["Content-Type"])
        assertEquals("{}", server.takeRequest().body?.utf8())
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `media URLs resolve against the server address`() {
        val descriptor = PlaybackDescriptorDto("hls", "application/vnd.apple.mpegurl", "/media/sessions/s1/1/root.m3u8", 1)

        assertEquals("http://tv.lan:9193/media/sessions/s1/1/root.m3u8", SofaKingApi("http://tv.lan:9193/", "t").mediaUrl(descriptor))
    }

    @Test
    fun `HLS descriptors get the MIME type Media3 recognizes and MPEG-TS passes through`() {
        assertEquals("application/x-mpegURL", mediaMimeType(PlaybackDescriptorDto("hls", "application/vnd.apple.mpegurl", "/x.m3u8", 1)))
        assertEquals("application/x-mpegURL", mediaMimeType(PlaybackDescriptorDto("hls", "Application/X-MpegURL", "/x", 1)))
        assertEquals("video/mp2t", mediaMimeType(PlaybackDescriptorDto("mpegts", "video/mp2t", "/x.ts", 1)))
    }

    @Test
    fun `canceling a stalled request closes its socket promptly`() = runBlocking {
        ServerSocket(0).use { socket ->
            val executor = Executors.newSingleThreadExecutor()
            val connected = CompletableDeferred<Unit>()
            val disconnected = CompletableDeferred<Boolean>()
            try {
                executor.submit {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {
                            // Consume the request headers before holding the response body open.
                        }
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{\"revision\":".toByteArray())
                            flush()
                        }
                        connected.complete(Unit)
                        disconnected.complete(reader.read() == -1)
                    }
                }
                val api = SofaKingApi("http://127.0.0.1:${socket.localPort}", "t")
                val loading = launch(Dispatchers.Default) { api.catalog(etag = null) }
                withTimeout(5000) {
                    connected.await()
                    loading.cancelAndJoin()
                    assertTrue(disconnected.await())
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
