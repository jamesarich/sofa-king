package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.ProbeConfig
import dev.jamesrich.sofaking.backend.health.ProbeWorker
import dev.jamesrich.sofaking.backend.playback.*
import dev.jamesrich.sofaking.backend.store.BackendStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.time.Clock
import kotlinx.coroutines.*

class LiveRuntime(val feeds: FeedService, val sessions: SessionManager, val relay: MediaRelay, private val http: HttpClient, private val scope: CoroutineScope) {
    suspend fun close() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        sessions.close()
        http.close()
    }
    companion object {
        suspend fun create(store: BackendStore, config: ServerConfig, clock: Clock): LiveRuntime {
            val configs = loadFeeds(config.sourcesFile)
            val http = HttpClient(CIO) {
                followRedirects = false
                install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 5000; socketTimeoutMillis = 15_000 }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val feeds = FeedService(configs, store, http, clock)
                feeds.initialize()
                val sessions = SessionManager(feeds, store, clock, config.maxSessions)
                val runtime = LiveRuntime(feeds, sessions, MediaRelay(http, feeds, sessions), http, scope)
                feeds.start(scope)
                scope.launch { while (isActive) { sessions.expire(); delay(1000) } }
                if (configs.any { it.probe }) {
                    val probes = ProbeConfig(parallelism = config.probeParallelism, restreamOrigins = config.restreamOrigins)
                    scope.launch { ProbeWorker(feeds, store, clock, config.ffmpeg, http, probes, onHealthChanged = sessions::healthChanged).run() }
                }
                return runtime
            } catch (failure: Throwable) { scope.cancel(); http.close(); throw failure }
        }
    }
}
