package dev.jamesrich.sofaking.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors

/** What only a real socket shows, on the JVM's OkHttp engine; request and answer handling is tested in common code. */
class SofaKingApiSocketTest {
    @Test
    fun `a request over the real engine carries the token and a JSON body`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"revision":"r1","from":"x","to":"y","channels":[]}""")
                    .build(),
            )

            SofaKingApi(server.url("/tv/").toString(), "secret").guide(GuideQueryDto(listOf("c1"), "a", "b", null))

            val request = server.takeRequest()
            assertEquals("/tv/api/v1/guide/query", request.url.encodedPath)
            assertEquals("Bearer secret", request.headers["Authorization"])
            assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
            assertEquals("""{"channelIds":["c1"],"from":"a","to":"b"}""", request.body?.utf8())
        }
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
                            write(
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{\"revision\":".toByteArray(),
                            )
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
