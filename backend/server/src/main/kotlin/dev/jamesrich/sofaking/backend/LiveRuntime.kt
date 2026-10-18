package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.dvr.Commercials
import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.backend.dvr.Recorder
import dev.jamesrich.sofaking.backend.dvr.Retention
import dev.jamesrich.sofaking.backend.health.ProbeConfig
import dev.jamesrich.sofaking.backend.health.ProbeWorker
import dev.jamesrich.sofaking.backend.http.hdhrDeviceId
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.library.*
import dev.jamesrich.sofaking.backend.logging.applyLogLevel
import dev.jamesrich.sofaking.backend.settings.RuntimeSettings
import dev.jamesrich.sofaking.backend.settings.RuntimeValues
import dev.jamesrich.sofaking.backend.logos.LogoCache
import dev.jamesrich.sofaking.backend.plex.HdhrDevice
import dev.jamesrich.sofaking.backend.plex.PlexSync
import dev.jamesrich.sofaking.backend.pairing.PairingService
import dev.jamesrich.sofaking.backend.playback.*
import dev.jamesrich.sofaking.backend.providers.ProviderRegistry
import dev.jamesrich.sofaking.backend.setup.SetupService
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.update.RunningBuild
import dev.jamesrich.sofaking.backend.update.UpdateChecker
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.time.Clock
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

private val runtimeLog = LoggerFactory.getLogger(LiveRuntime::class.java)

/** The runtime's scope: a background coroutine that fails is logged by its name and exception class, and the others keep running. */
internal fun backgroundScope(report: (String) -> Unit = runtimeLog::error): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { context, failure ->
            report("background coroutine failed name=${context[CoroutineName]?.name ?: "unnamed"} class=${failure.javaClass.simpleName}")
        },
    )

/** Runs [action] every [periodMillis] until cancelled; a failed run is logged by exception class and the loop goes on. */
internal fun CoroutineScope.launchEvery(name: String, periodMillis: Long, report: (String) -> Unit = runtimeLog::warn, action: suspend () -> Unit): Job =
    launch(CoroutineName(name)) {
        while (isActive) {
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { report("$name failed class=${e.javaClass.simpleName}") }
            delay(periodMillis)
        }
    }

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
    private val http: HttpClient,
    private val scope: CoroutineScope,
    /** The lineup policy when `SOFA_POLICY` names one; it then builds the lineup. */
    val policy: PolicyService? = null,
    /** Plex sync when `SOFA_PLEX_URL` is set. */
    val plex: PlexSync? = null,
    /** When the runtime started, for the uptime the status reports. */
    val startedAt: java.time.Instant = java.time.Instant.now(),
    /** The background jobs, for the portal's Jobs page. */
    val jobs: Jobs = Jobs(Clock.systemUTC()),
    /** The settings the operator can change without a restart. */
    val settings: RuntimeSettings = RuntimeSettings(store, RuntimeValues.DEFAULT, Clock.systemUTC()),
    /** The probe worker, when probes run; the portal's "Probe now" goes through it. */
    val probes: ProbeWorker? = null,
    /** The pairing codes the portal issues and `POST /api/v1/pair` claims. */
    val pairing: PairingService = PairingService(Clock.systemUTC()),
    /** The published-image check and the hand-off to the host's updater; null when the runtime was built without a config. */
    val updates: UpdateChecker? = null,
    /** Setup from the portal and the API: the source file, the starter policy and the restart that reads them. */
    val setup: SetupService? = null,
    /** The media libraries; null for a runtime built without a config. */
    val libraries: LibraryService? = null,
    /** Library files being played; null without libraries. */
    val vod: VodSessions? = null,
    /** The DVR; null when the runtime was built without a config. */
    val dvr: Dvr? = null,
) {
    suspend fun close() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        sessions.close()
        vod?.close()
        remux.close()
        http.close()
    }
    companion object {
        /** [probes] false keeps the probe worker from starting, for tests over fixtures; [restart] is what a setup write ends the process with. */
        suspend fun create(
            store: BackendStore, config: ServerConfig, clock: Clock, policyDebounceMillis: Long = 10_000, probes: Boolean = true, plexDebounceMillis: Long = 120_000,
            restart: () -> Unit = SetupService::exitForRestart,
        ): LiveRuntime {
            val started = clock.instant()
            val sources = loadSources(config.sourcesFile)
            val providerIds = sources.providers.mapTo(HashSet()) { provider -> provider.id }
            val loadedPolicy = config.policyFile?.let { loadPolicy(it, providerIds) }
            val http = HttpClient(CIO) {
                followRedirects = false
                install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 5000; socketTimeoutMillis = 15_000 }
            }
            val scope = backgroundScope()
            val jobs = Jobs(clock)
            val settings = RuntimeSettings(store, RuntimeValues.of(config), clock)
            for (problem in settings.load()) runtimeLog.warn("runtime settings: dropped a saved value: {}", problem)
            // The level the operator saved outranks the environment's from here on; logback read the environment's at start.
            settings.onChange { applyLogLevel(it.logLevel) }
            try {
                val policy = UpstreamPolicy(allowLoopback = config.allowLoopbackUpstreams)
                lateinit var logos: LogoCache
                var policyService: PolicyService? = null
                var dvrService: Dvr? = null
                val providers = ProviderRegistry.create(sources.providers, http, store, clock, policy)
                val feeds = FeedService(sources.providers, store, http, clock, policy, providers, jobs) { logos.requestSweep(); policyService?.requestRun("feeds") }
                feeds.initialize()
                lateinit var lineup: LineupService
                val guides = GuideService(sources.allGuides, store, http, clock, policy, providers, onImported = { policyService?.requestRun("guides"); dvrService?.requestSchedule("guides") }, jobs = jobs) {
                    val mapped = lineup.wanted()
                    val extra = policyService?.wantedGuides().orEmpty()
                    if (extra.isEmpty()) mapped else (mapped.keys + extra.keys).associateWith { id -> mapped[id].orEmpty() + extra[id].orEmpty() }
                }
                guides.initialize()
                lineup = LineupService(store, clock, feeds, guides) { logos.requestSweep() }
                lineup.initialize()
                val plex = config.plex?.let { plexConfig ->
                    val ports = config.hdhr.publicPorts(config.port)
                    PlexSync(
                        plexConfig, http, clock, { val serverId = store.serverId(); List(config.hdhr.devices) { n -> HdhrDevice(n, hdhrDeviceId(config.hdhr, serverId, n), ports[n]) } },
                        plexDebounceMillis, settings.current.plexSyncMinutes * 60_000L, initial = store.plexStatus(), persist = store::savePlexStatus, jobs = jobs,
                    )
                }
                val catalog = CatalogService(feeds, guides, lineup, store, clock)
                logos = LogoCache(store, http, clock, config.dataDirectory.resolve("logos"), policy, { lineup.entries().mapNotNull(catalog::logoSource) }, jobs = jobs)
                logos.initialize()
                val sessions = SessionManager(catalog, store, clock, settings.current.maxSessions)
                settings.onChange { values -> sessions.capacity = values.maxSessions; plex?.setInterval(values.plexSyncMinutes) }
                policyService = loadedPolicy?.let { loaded ->
                    lateinit var service: PolicyService
                    val sizing = if (config.hdhr.enabled) HdhrSizing(config.hdhr.devices, config.hdhr.deviceSize) else null
                    val file = PolicyFile(loaded, providerIds) { service.requestRun("policy") }
                    service = PolicyService(
                        file, feeds, guides, lineup, store, clock, sizing, policyDebounceMillis,
                        onLineupChanged = { plex?.requestSync("policy") }, extraFindings = { plex?.findings().orEmpty() }, jobs = jobs,
                    )
                    service
                }
                feeds.start(scope)
                guides.start(scope)
                logos.start(scope)
                logos.requestSweep()
                policyService?.start(scope)
                policyService?.requestRun("startup")
                plex?.start(scope)
                plex?.requestSync("startup")
                // Under a policy the worker probes what the lineup's stations reference, whatever the providers' probe flags.
                val service = policyService
                val worker = if (probes && (service != null || sources.providers.any { it.probe })) {
                    val probeConfig =
                        ProbeConfig(
                            parallelism = config.probeParallelism,
                            restreamOrigins = config.restreamOrigins,
                            observationRetention = java.time.Duration.ofDays(config.observationRetentionDays.toLong()),
                        )
                    val targets = service?.let { { it.probeTargets() } }
                    ProbeWorker(
                        feeds, store, clock, config.ffmpeg, http, probeConfig, onObserved = { id -> sessions.healthChanged(id) },
                        onHealthChanged = { service?.requestRun("health") }, targets = targets,
                    )
                } else null
                val dvr = Dvr(
                    store, lineup, guides, Recorder(config.ffmpeg, sessions, feeds, clock), config.recordingsDirectory, clock, { settings.current.maxSessions },
                    { java.time.Duration.ofDays(settings.current.guideDays.toLong()) },
                    retention = { settings.current.let { Retention(it.recordingsFreeGib, it.recordingsKeepDays) } },
                    commercials = config.comskip?.let { Commercials(it, config.dataDirectory.resolve("dvr")) }, jobs = jobs,
                ).also { dvrService = it }
                val updates = UpdateChecker(http, clock, config.dataDirectory, config.updateImage, RunningBuild(config.revision, config.version), jobs)
                updates.start(scope)
                val libraries = LibraryService(
                    store, clock, listOf(MediaRoot("Media", config.mediaDirectory)), LibraryScanner(MediaProbe(ffprobeBeside(config.ffmpeg))::probe), jobs,
                    metadata = config.tmdb?.let { LibraryMetadata(store, TmdbClient(http, it), clock, it.region) },
                    artwork = LibraryArtwork(http, config.dataDirectory.resolve("artwork")),
                )
                libraries.start(scope)
                val vod = VodSessions(libraries.catalog, clock, config.ffmpeg, config.dataDirectory.resolve("transcode"), config.maxTranscodes)
                vod.start()
                val runtime = LiveRuntime(
                    store, feeds, guides, lineup, catalog, sessions, MediaRelay(http, feeds, sessions, scope), TsRemux(config.ffmpeg), logos, http, scope,
                    policyService, plex, started, jobs, settings, worker, PairingService(clock), updates, SetupService(config, restart), libraries, vod, dvr,
                )
                dvr.start(scope)
                dvr.requestSchedule("startup")
                scope.launchEvery("session-expire", 1000) { sessions.expire() }
                scope.launchEvery("vod-expire", 5000) { vod.expire() }
                if (worker != null) scope.launch(CoroutineName("probe-worker")) { worker.run() }
                return runtime
            } catch (failure: Throwable) { scope.cancel(); http.close(); throw failure }
        }
    }
}
