package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.ProbeConfig
import dev.jamesrich.sofaking.backend.health.ProbeWorker
import dev.jamesrich.sofaking.backend.playback.*
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.time.Clock
import kotlinx.coroutines.*

class LiveRuntime(
    val store: BackendStore,
    val feeds: FeedService,
    val guides: GuideService,
    val lineup: LineupService,
    val catalog: CatalogService,
    val sessions: SessionManager,
    val relay: MediaRelay,
    val remux: TsRemux,
    private val http: HttpClient,
    private val scope: CoroutineScope,
) {
    suspend fun close() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        sessions.close()
        remux.close()
        http.close()
    }
    companion object {
        suspend fun create(store: BackendStore, config: ServerConfig, clock: Clock): LiveRuntime {
            val sources = loadSources(config.sourcesFile)
            val http = HttpClient(CIO) {
                followRedirects = false
                install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 5000; socketTimeoutMillis = 15_000 }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val policy = UpstreamPolicy(allowLoopback = config.allowLoopbackUpstreams)
                val feeds = FeedService(sources.providers, store, http, clock, policy)
                feeds.initialize()
                lateinit var lineup: LineupService
                val guides = GuideService(sources.allGuides, store, http, clock, policy) { lineup.wanted() }
                guides.initialize()
                lineup = LineupService(store, clock, feeds, guides)
                lineup.initialize()
                val catalog = CatalogService(feeds, guides, lineup, store, clock)
                val sessions = SessionManager(catalog, store, clock, config.maxSessions)
                val runtime = LiveRuntime(store, feeds, guides, lineup, catalog, sessions, MediaRelay(http, feeds, sessions, scope), TsRemux(config.ffmpeg), http, scope)
                feeds.start(scope)
                guides.start(scope)
                scope.launch { while (isActive) { sessions.expire(); delay(1000) } }
                if (sources.providers.any { it.probe }) {
                    val probes =
                        ProbeConfig(
                            parallelism = config.probeParallelism,
                            restreamOrigins = config.restreamOrigins,
                            observationRetention = java.time.Duration.ofDays(config.observationRetentionDays.toLong()),
                        )
                    scope.launch { ProbeWorker(feeds, store, clock, config.ffmpeg, http, probes, onHealthChanged = sessions::healthChanged).run() }
                }
                return runtime
            } catch (failure: Throwable) { scope.cancel(); http.close(); throw failure }
        }
    }
}
