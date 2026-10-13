package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.store.IdentityStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.time.Clock
import kotlinx.coroutines.runBlocking

/**
 * The backend on a real Netty socket. The test host hands streamed bodies to a background job and returns from
 * the handler at once, so anything about a response's lifetime, a client hanging up, or an external process
 * reading the server is verified here.
 */
class RealServer(store: IdentityStore, clock: Clock, runtime: LiveRuntime, token: String, outputs: OutputConfig = OutputConfig(), hdhr: HdhrConfig = HdhrConfig()) : AutoCloseable {
    private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { backendModule(store, clock, runtime, token, outputs = outputs, hdhr = hdhr) }
    val base: String

    init {
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        base = "http://127.0.0.1:$port"
    }

    override fun close() {
        server.stop(100, 2000)
    }
}
