package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.ProbeConfig
import dev.jamesrich.sofaking.backend.health.ProbeWorker
import dev.jamesrich.sofaking.backend.logos.LogoCache
import dev.jamesrich.sofaking.backend.playback.*
import dev.jamesrich.sofaking.backend.providers.ProviderRegistry
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
    val logos: LogoCache,
    val curation: CurationService,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    /** The lineup policy when `SOFA_POLICY` names one; read-only, the lineup is not built from it. */
    val policy: PolicyService? = null,
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
            val loadedPolicy = config.policyFile?.let { loadPolicy(it, sources.providers.mapTo(HashSet()) { provider -> provider.id }) }
            val http = HttpClient(CIO) {
                followRedirects = false
                install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 5000; socketTimeoutMillis = 15_000 }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val policy = UpstreamPolicy(allowLoopback = config.allowLoopbackUpstreams)
                lateinit var logos: LogoCache
                lateinit var curation: CurationService
                val providers = ProviderRegistry.create(sources.providers, http, store, clock, policy)
                // A new snapshot can change what curation would do, so an import triggers a run as well as a logo sweep.
                val feeds = FeedService(sources.providers, store, http, clock, policy, providers) { logos.requestSweep(); curation.requestRun() }
                feeds.initialize()
                lateinit var lineup: LineupService
                val guides = GuideService(sources.allGuides, store, http, clock, policy, providers) { lineup.wanted() }
                guides.initialize()
                lineup = LineupService(store, clock, feeds, guides) { logos.requestSweep() }
                lineup.initialize()
                curation = CurationService(store, clock, feeds, guides, lineup, onSettingsChanged = { logos.requestSweep() })
                curation.initialize()
                val catalog = CatalogService(feeds, guides, lineup, store, clock, curation::retiredLogoHosts)
                logos = LogoCache(store, http, clock, config.dataDirectory.resolve("logos"), policy, { lineup.entries().mapNotNull(catalog::logoSource) })
                logos.initialize()
                val sessions = SessionManager(catalog, store, clock, config.maxSessions)
                val policyService = loadedPolicy?.let { PolicyService(it, feeds, guides, lineup, store, clock) }
                val runtime = LiveRuntime(
                    store, feeds, guides, lineup, catalog, sessions, MediaRelay(http, feeds, sessions, scope), TsRemux(config.ffmpeg), logos, curation, http, scope,
                    policyService,
                )
                feeds.start(scope)
                guides.start(scope)
                logos.start(scope)
                logos.requestSweep()
                curation.start(scope)
                curation.requestRun()
                scope.launch { while (isActive) { sessions.expire(); delay(1000) } }
                if (sources.providers.any { it.probe }) {
                    val probes =
                        ProbeConfig(
                            parallelism = config.probeParallelism,
                            restreamOrigins = config.restreamOrigins,
                            observationRetention = java.time.Duration.ofDays(config.observationRetentionDays.toLong()),
                        )
                    scope.launch { ProbeWorker(feeds, store, clock, config.ffmpeg, http, probes, onObserved = { id -> sessions.healthChanged(id) }, onHealthChanged = { curation.requestRun() }).run() }
                }
                return runtime
            } catch (failure: Throwable) { scope.cancel(); http.close(); throw failure }
        }
    }
}
