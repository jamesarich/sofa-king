package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.http.backendModule
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.time.Clock
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking<Unit> {
    val config = ServerConfig.fromEnvironment(System.getenv())
    openStore(config.dataDirectory).use { store ->
        val server = embeddedServer(Netty, host = config.host, port = config.port) {
            backendModule(store, Clock.systemUTC())
        }
        val hook = Thread({ server.stop(1000, 5000) }, "sofa-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            server.start(wait = true)
        } finally {
            server.stop(1000, 5000)
            try { Runtime.getRuntime().removeShutdownHook(hook) }
            catch (_: IllegalStateException) { /* JVM shutdown is already running. */ }
        }
    }
}
