package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.http.backendModule
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main(): Unit = runBlocking<Unit> {
    val log = LoggerFactory.getLogger("dev.jamesrich.sofaking.backend.Main")
    val config = ServerConfig.fromEnvironment(System.getenv())
    val store = openStore(config.dataDirectory)
    val runtime =
        try {
            LiveRuntime.create(store, config, Clock.systemUTC())
        } catch (failure: Throwable) {
            store.close()
            throw failure
        }
    val server = embeddedServer(Netty, host = config.host, port = config.port) {
        backendModule(store, Clock.systemUTC(), runtime, config.apiToken, config.adminToken)
    }
    // The JVM halts as soon as its shutdown hooks return, so the hook itself runs the whole ordered close.
    val closed = AtomicBoolean(false)
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(1000, 5000)
        log.info("shutdown: server stopped")
        runBlocking { runtime.close() }
        log.info("shutdown: runtime closed")
        store.close()
        log.info("shutdown: store closed")
    }
    val hook = Thread(::shutdown, "sofa-shutdown")
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        server.start(wait = true)
    } finally {
        shutdown()
        try { Runtime.getRuntime().removeShutdownHook(hook) }
        catch (_: IllegalStateException) { /* JVM shutdown is already running. */ }
    }
}
