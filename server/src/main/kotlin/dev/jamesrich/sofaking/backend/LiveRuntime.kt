package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.ProbeConfig
import dev.jamesrich.sofaking.backend.health.ProbeWorker
import dev.jamesrich.sofaking.backend.http.hdhrDeviceId
import dev.jamesrich.sofaking.backend.logos.LogoCache
import dev.jamesrich.sofaking.backend.plex.HdhrDevice
import dev.jamesrich.sofaking.backend.plex.PlexSync
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
    /** The lineup policy when `SOFA_POLICY` names one; it then builds the lineup and curation does not run. */
    val policy: PolicyService? = null,
    /** The curation loop, running only without a policy. */
    val curationLoop: Job? = null,
    /** Plex sync when `SOFA_PLEX_URL` is set. */
    val plex: PlexSync? = null,
) {
    suspend fun close() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        sessions.close()
        remux.close()
        http.close()
    }
    companion object {
        /** [probes] false keeps the probe worker from starting, for tests over fixtures. */
        suspend fun create(
            store: BackendStore, config: ServerConfig, clock: Clock, policyDebounceMillis: Long = 10_000, probes: Boolean = true, plexDebounceMillis: Long = 120_000,
        ): LiveRuntime {
            val sources = loadSources(config.sourcesFile)
            val providerIds = sources.providers.mapTo(HashSet()) { provider -> provider.id }
            val loadedPolicy = config.policyFile?.let { loadPolicy(it, providerIds) }
            val http = HttpClient(CIO) {
                followRedirects = false
                install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 5000; socketTimeoutMillis = 15_000 }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val policy = UpstreamPolicy(allowLoopback = config.allowLoopbackUpstreams)
                lateinit var logos: LogoCache
                lateinit var curation: CurationService
                var policyService: PolicyService? = null
                // A new snapshot, guide or health state can change the lineup: with a policy it triggers a policy run, else curation.
                fun lineupInputsChanged(cause: String) {
                    val service = policyService
                    if (service != null) service.requestRun(cause) else curation.requestRun()
                }
                val providers = ProviderRegistry.create(sources.providers, http, store, clock, policy)
                val feeds = FeedService(sources.providers, store, http, clock, policy, providers) { logos.requestSweep(); lineupInputsChanged("feeds") }
                feeds.initialize()
                lateinit var lineup: LineupService
                val guides = GuideService(sources.allGuides, store, http, clock, policy, providers, onImported = { policyService?.requestRun("guides") }) { lineup.wanted() }
                guides.initialize()
                lineup = LineupService(store, clock, feeds, guides) { logos.requestSweep() }
                lineup.initialize()
                val plex = config.plex?.let { plexConfig ->
                    val ports = config.hdhr.publicPorts(config.port)
                    PlexSync(plexConfig, http, clock, { val serverId = store.serverId(); List(config.hdhr.devices) { n -> HdhrDevice(n, hdhrDeviceId(config.hdhr, serverId, n), ports[n]) } }, plexDebounceMillis)
                }
                curation = CurationService(store, clock, feeds, guides, lineup, onSettingsChanged = { logos.requestSweep() }, onLineupChanged = { plex?.requestSync("curation") })
                curation.initialize()
                val retiredLogoHosts: () -> Set<String> = if (loadedPolicy == null) curation::retiredLogoHosts else { { emptySet() } }
                val catalog = CatalogService(feeds, guides, lineup, store, clock, retiredLogoHosts)
                logos = LogoCache(store, http, clock, config.dataDirectory.resolve("logos"), policy, { lineup.entries().mapNotNull(catalog::logoSource) })
                logos.initialize()
                val sessions = SessionManager(catalog, store, clock, config.maxSessions)
                policyService = loadedPolicy?.let { loaded ->
                    lateinit var service: PolicyService
                    val sizing = if (config.hdhr.enabled) HdhrSizing(config.hdhr.devices, config.hdhr.deviceSize) else null
                    val file = PolicyFile(loaded, providerIds) { service.requestRun("policy") }
                    service = PolicyService(
                        file, feeds, guides, lineup, store, clock, sizing, policyDebounceMillis,
                        onLineupChanged = { plex?.requestSync("policy") }, extraFindings = { plex?.findings().orEmpty() },
                    )
                    service
                }
                feeds.start(scope)
                guides.start(scope)
                logos.start(scope)
                logos.requestSweep()
                val curationLoop = if (policyService == null) curation.start(scope).also { curation.requestRun() } else null
                policyService?.start(scope)
                policyService?.requestRun("startup")
                plex?.start(scope)
                plex?.requestSync("startup")
                val runtime = LiveRuntime(
                    store, feeds, guides, lineup, catalog, sessions, MediaRelay(http, feeds, sessions, scope), TsRemux(config.ffmpeg), logos, curation, http, scope,
                    policyService, curationLoop, plex,
                )
                scope.launch { while (isActive) { sessions.expire(); delay(1000) } }
                // Under a policy the worker probes what the lineup's stations reference, whatever the providers' probe flags.
                val service = policyService
                if (probes && (service != null || sources.providers.any { it.probe })) {
                    val probeConfig =
                        ProbeConfig(
                            parallelism = config.probeParallelism,
                            restreamOrigins = config.restreamOrigins,
                            observationRetention = java.time.Duration.ofDays(config.observationRetentionDays.toLong()),
                        )
                    val targets = service?.let { { it.probeTargets() } }
                    scope.launch {
                        ProbeWorker(
                            feeds, store, clock, config.ffmpeg, http, probeConfig, onObserved = { id -> sessions.healthChanged(id) },
                            onHealthChanged = { lineupInputsChanged("health") }, targets = targets,
                        ).run()
                    }
                }
                return runtime
            } catch (failure: Throwable) { scope.cancel(); http.close(); throw failure }
        }
    }
}
