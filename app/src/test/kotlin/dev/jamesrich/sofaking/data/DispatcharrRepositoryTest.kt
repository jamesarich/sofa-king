package dev.jamesrich.sofaking.data

import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcharrRepositoryTest {
    @Test
    fun `canceling a stalled playlist closes its socket promptly`() = runBlocking {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val connected = CompletableDeferred<Unit>()
            val disconnected = CompletableDeferred<Boolean>()
            try {
                executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {
                            // Consume the request headers before holding the response body open.
                        }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n#EXTM3U\n".toByteArray())
                            flush()
                        }
                        connected.complete(Unit)
                        disconnected.complete(reader.read() == -1)
                    }
                }
                val repository = DispatcharrRepository("http://127.0.0.1:${server.localPort}", "livingroom")
                val loading = launch(Dispatchers.Default) { repository.loadChannels() }
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
