package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.admin.PlexStatusSource
import dev.jamesrich.sofaking.backend.http.backendModule
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main(): Unit = runBlocking<Unit> {
    val log = LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.Main")
    val config = ServerConfig.load(System.getenv(), log::info)
    val clock = Clock.systemUTC()
    val store = openStore(config.dataDirectory, clock)
    val runtime =
        try {
            LiveRuntime.create(store, config, clock)
        } catch (failure: Throwable) {
            store.close()
            throw failure
        }
    val ports = listOf(config.port) + if (config.hdhr.enabled) config.hdhr.extraPorts else emptyList()
    val plexStatus = runtime.plex?.let { sync -> PlexStatusSource { sync.status() } }
    // Both Ktor's shutdown hook and main's finally close; whichever comes second waits for the first to finish,
    // so the hook cannot return (and the JVM halt) while main is still closing the store.
    val closing = lazy {
        runBlocking { runtime.close() }
        log.info("shutdown: runtime closed")
        store.close()
        log.info("shutdown: store closed")
    }
    fun close() = closing.value
    // Ktor stops with its defaults, a 1 s grace period and a 5 s timeout.
    val server = embeddedServer(Netty, configure = { ports.forEach { p -> connector { host = config.host; port = p } } }) {
        backendModule(store, clock, runtime, config.apiToken, config.adminToken, config.outputs, config.hdhr, plexStatus, config)
        // Ktor's own shutdown hook stops the server, which raises this before the hook returns and the JVM halts.
        monitor.subscribe(ApplicationStopped) {
            log.info("shutdown: server stopped")
            close()
        }
    }
    try {
        server.start(wait = true)
    } finally {
        close()
    }
}
