package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.ads.AdListener
import dev.jamesrich.sofaking.backend.ads.BreakLibrary
import dev.jamesrich.sofaking.backend.catalog.CatalogService
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.GuideService
import dev.jamesrich.sofaking.backend.catalog.HdhrSizing
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.catalog.PolicyFile
import dev.jamesrich.sofaking.backend.catalog.PolicyService
import dev.jamesrich.sofaking.backend.catalog.loadPolicy
import dev.jamesrich.sofaking.backend.catalog.loadSources
import dev.jamesrich.sofaking.backend.dvr.Commercials
import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.backend.dvr.Recorder
import dev.jamesrich.sofaking.backend.dvr.Retention
import dev.jamesrich.sofaking.backend.health.ProbeConfig
import dev.jamesrich.sofaking.backend.health.ProbeWorker
import dev.jamesrich.sofaking.backend.http.HdhrAccess
import dev.jamesrich.sofaking.backend.http.hdhrDeviceId
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.library.FfmpegFingerprinter
import dev.jamesrich.sofaking.backend.library.FfmpegSheetMaker
import dev.jamesrich.sofaking.backend.library.IntroDetector
import dev.jamesrich.sofaking.backend.library.LibraryArtwork
import dev.jamesrich.sofaking.backend.library.LibraryMetadata
import dev.jamesrich.sofaking.backend.library.LibraryScanner
import dev.jamesrich.sofaking.backend.library.LibraryService
import dev.jamesrich.sofaking.backend.library.MediaProbe
import dev.jamesrich.sofaking.backend.library.MediaRoot
import dev.jamesrich.sofaking.backend.library.ProgramFactsLookup
import dev.jamesrich.sofaking.backend.library.ScrubSheets
import dev.jamesrich.sofaking.backend.library.TmdbClient
import dev.jamesrich.sofaking.backend.library.VodSessions
import dev.jamesrich.sofaking.backend.library.ffprobeBeside
import dev.jamesrich.sofaking.backend.logging.applyLogLevel
import dev.jamesrich.sofaking.backend.logos.LogoCache
import dev.jamesrich.sofaking.backend.pairing.IssuedTokens
import dev.jamesrich.sofaking.backend.pairing.PairingService
import dev.jamesrich.sofaking.backend.playback.LoopbackRelay
import dev.jamesrich.sofaking.backend.playback.MediaRelay
import dev.jamesrich.sofaking.backend.playback.SegmentAudio
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.playback.TsRemux
import dev.jamesrich.sofaking.backend.plex.HdhrDevice
import dev.jamesrich.sofaking.backend.plex.PlexSync
import dev.jamesrich.sofaking.backend.providers.ProviderClient
import dev.jamesrich.sofaking.backend.providers.ProviderContext
import dev.jamesrich.sofaking.backend.providers.ProviderRegistry
import dev.jamesrich.sofaking.backend.settings.LearnedBreaks
import dev.jamesrich.sofaking.backend.settings.RuntimeSettings
import dev.jamesrich.sofaking.backend.settings.RuntimeValues
import dev.jamesrich.sofaking.backend.setup.SetupService
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.plexStatus
import dev.jamesrich.sofaking.backend.store.recordAdChannel
import dev.jamesrich.sofaking.backend.store.savePlexStatus
import dev.jamesrich.sofaking.backend.update.RunningBuild
import dev.jamesrich.sofaking.backend.update.UpdateChecker
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.virtual.VirtualChannelService
import dev.jamesrich.sofaking.backend.virtual.VirtualClient
import dev.jamesrich.sofaking.backend.virtual.VirtualStreams
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val assemblyLog = LoggerFactory.getLogger(LiveRuntime::class.java)

/** How often the break library saves what it learned. */
private const val LIBRARY_FLUSH_MS = 300_000L

/** How often lapsed playback sessions, and lapsed library playback sessions, are swept. */
private const val SESSION_EXPIRE_MS = 1_000L
private const val VOD_EXPIRE_MS = 5_000L
private const val VIRTUAL_EXPIRE_MS = 10_000L

private const val REQUEST_TIMEOUT_MS = 60_000L
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val SOCKET_TIMEOUT_MS = 15_000L
private const val MINUTE_MS = 60_000L

/** What [LiveRuntime.create] takes beside the store, the config and the clock. */
internal class RuntimeOptions(
    val policyDebounceMillis: Long,
    val probes: Boolean,
    val plexDebounceMillis: Long,
    val restart: () -> Unit,
)

/**
 * Builds a [LiveRuntime] from [config]: the source and policy files first, which may stop the start before anything
 * runs, then the services in the order they depend on each other, each started once what it calls is.
 */
internal class RuntimeAssembly(
    private val store: BackendStore,
    private val config: ServerConfig,
    private val clock: Clock,
    private val options: RuntimeOptions,
) {
    private val sources = loadSources(config.sourcesFile)
    private val providerIds = sources.providers.mapTo(HashSet()) { provider -> provider.id }
    private val loadedPolicy = config.policyFile?.let { loadPolicy(it, providerIds) }

    /** Closed, with [scope] canceled, when the assembly fails before the runtime is whole. */
    val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
    }
    val scope = backgroundScope()
    private val jobs = Jobs(clock)
    private val settings = RuntimeSettings(store, RuntimeValues.of(config), clock)
    private val upstream = UpstreamPolicy(allowLoopback = config.allowLoopbackUpstreams)

    private lateinit var feeds: FeedService
    private lateinit var guides: GuideService
    private lateinit var lineup: LineupService
    private lateinit var catalog: CatalogService
    private lateinit var logos: LogoCache
    private lateinit var sessions: SessionManager
    private lateinit var library: BreakLibrary
    private lateinit var relay: MediaRelay
    private lateinit var loopback: LoopbackRelay
    private var plex: PlexSync? = null
    private var policy: PolicyService? = null
    private var dvrService: Dvr? = null
    private lateinit var virtual: VirtualChannelService

    suspend fun assemble(started: Instant): LiveRuntime {
        for (problem in settings.load()) assemblyLog.warn("runtime settings: dropped a saved value: {}", problem)
        // The level the operator saved outranks the environment's from here on; logback
        // read the environment's at start.
        settings.onChange { applyLogLevel(it.logLevel) }
        val libraries = libraries()
        liveCatalog(libraries)
        plex = plexSync()
        catalog = CatalogService(feeds, guides, lineup, store, clock)
        logos = logoCache()
        playback()
        policy = policyService()
        feeds.start(scope)
        guides.start(scope)
        logos.start(scope)
        logos.requestSweep()
        policy?.start(scope)
        policy?.requestRun("startup")
        plex?.start(scope)
        plex?.requestSync("startup")
        val audio = SegmentAudio(config.ffmpeg)
        relay = MediaRelay(http, feeds, sessions, scope, audio = audio)
        val listener = adListener(audio)
        loopback = LoopbackRelay(http, upstream, relay.permits)
        val worker = probeWorker()
        val dvr = dvr().also { dvrService = it }
        val updates = UpdateChecker(
            http,
            clock,
            config.dataDirectory,
            config.updateImage,
            RunningBuild(config.revision, config.version),
            jobs,
        ).also { it.start(scope) }
        val (vod, streams) = playback(dvr, libraries)
        val runtime = LiveRuntime(
            store, feeds, guides, lineup, catalog, sessions, relay,
            TsRemux(
                config.ffmpeg,
                loopback,
                config.qualityFloors,
            ),
            logos, http,
            scope, policy, plex, started, jobs, settings, worker, PairingService(clock), updates,
            SetupService(config, options.restart, checkTmdb = { TmdbClient(http, it).check() }),
            libraries, vod, dvr, loopback,
            HdhrAccess(config.hdhr.allow, ServerConfig.isLoopback(config.host), store, clock).also { it.load() },
            listener,
            config.tmdb?.let { ProgramFactsLookup(TmdbClient(http, it), clock) },
            IssuedTokens(store, clock),
            virtual = virtual,
            virtualStreams = streams,
        )
        dvr.start(scope)
        dvr.requestSchedule("startup")
        scope.launchEvery("session-expire", SESSION_EXPIRE_MS) { sessions.expire() }
        scope.launchEvery("vod-expire", VOD_EXPIRE_MS) { vod.expire() }
        scope.launchEvery("virtual-expire", VIRTUAL_EXPIRE_MS) { streams.expire() }
        if (worker != null) scope.launch(CoroutineName("probe-worker")) { worker.run() }
        return runtime
    }

    /** The feeds, the guides they and the policy want, and the lineup over both. */
    private suspend fun liveCatalog(libraries: LibraryService) {
        val virtualIds = sources.providers.filter { it.service == VirtualClient.SERVICE }.map { it.id }
        virtual = VirtualChannelService(store, libraries.catalog, clock) {
            for (id in virtualIds) {
                feeds.requestRefresh(id)
                guides.requestRefresh(id)
            }
        }
        val local = mapOf<String, (ProviderContext) -> ProviderClient>(
            VirtualClient.SERVICE to
                { VirtualClient(virtual) { id -> loopback.localUrl(id, VirtualStreams.LIVE_PLAYLIST) } },
        )
        val providers = ProviderRegistry.create(sources.providers, http, store, clock, upstream, local)
        feeds = FeedService(sources.providers, store, http, clock, upstream, providers, jobs) {
            logos.requestSweep()
            policy?.requestRun("feeds")
        }
        feeds.initialize()
        guides = GuideService(sources.allGuides, store, http, clock, upstream, providers, onImported = {
            policy?.requestRun("guides")
            dvrService?.requestSchedule("guides")
        }, jobs = jobs) {
            val mapped = lineup.wanted()
            val extra = policy?.lastApplied()?.wantedGuides(guides.coverage, clock.instant()).orEmpty()
            if (extra.isEmpty()) {
                mapped
            } else {
                (mapped.keys + extra.keys).associateWith { id -> mapped[id].orEmpty() + extra[id].orEmpty() }
            }
        }
        guides.initialize()
        lineup = LineupService(store, clock, feeds, guides) { logos.requestSweep() }
        lineup.initialize()
    }

    private suspend fun plexSync(): PlexSync? {
        val plexConfig = config.plex ?: return null
        val ports = config.hdhr.publicPorts(config.port)
        return PlexSync(
            plexConfig,
            http,
            clock,
            {
                val serverId = store.serverId()
                List(config.hdhr.devices) { n -> HdhrDevice(n, hdhrDeviceId(config.hdhr, serverId, n), ports[n]) }
            },
            options.plexDebounceMillis,
            settings.current.plexSyncMinutes * MINUTE_MS,
            initial = store.plexStatus(),
            persist = store::savePlexStatus,
            jobs = jobs,
        )
    }

    private suspend fun logoCache(): LogoCache = LogoCache(
        store,
        http,
        clock,
        config.dataDirectory.resolve("logos"),
        upstream,
        { lineup.entries().mapNotNull(catalog::logoSource) },
        jobs = jobs,
    ).also { it.initialize() }

    /** The playback sessions and the break library they learn into, both following the settings. */
    private suspend fun playback() {
        sessions = SessionManager(catalog, store, clock, settings.current.maxSessions)
        library = BreakLibrary(store, clock).also { it.load() }
        sessions.library = library
        scope.launch(CoroutineName("ad-library")) {
            while (isActive) {
                delay(LIBRARY_FLUSH_MS)
                runCatchingCancellable { library.flush() }
                    .onFailure { assemblyLog.warn("ad library save failed: {}", it.toString()) }
            }
        }
        settings.onChange { values ->
            sessions.capacity = values.maxSessions
            sessions.learnedBreaks = LearnedBreaks.of(values.learnedBreaks) ?: LearnedBreaks.SHADOW
            plex?.setInterval(values.plexSyncMinutes)
        }
        sessions.onCensus = { channel, provider, census ->
            scope.launch { store.recordAdChannel(channel, provider, census, clock.instant()) }
        }
    }

    private fun policyService(): PolicyService? {
        val loaded = loadedPolicy ?: return null
        lateinit var service: PolicyService
        val sizing = if (config.hdhr.enabled) HdhrSizing(config.hdhr.devices, config.hdhr.deviceSize) else null
        val file = PolicyFile(loaded, providerIds) { service.requestRun("policy") }
        service = PolicyService(
            file, feeds, guides, lineup, store, clock, sizing, options.policyDebounceMillis,
            onLineupChanged = { plex?.requestSync("policy") },
            extraFindings = { plex?.findings().orEmpty() },
            jobs = jobs,
        )
        return service
    }

    private fun adListener(audio: SegmentAudio): AdListener = AdListener(
        http, feeds, sessions, store, library, audio, relay.permits, clock,
        channels = { lineup.entries().filter { it.enabled }.map { it.id } },
        slots = { settings.current.adListeners },
        mode = { sessions.learnedBreaks },
    ).also { it.start(scope) }

    /** The probe worker, unless probes are off or nothing asks for them. */
    private fun probeWorker(): ProbeWorker? {
        // Under a policy the worker probes what the lineup's stations reference,
        // whatever the providers' probe flags.
        val service = policy
        if (!options.probes || (service == null && sources.providers.none { it.probe })) return null
        val probeConfig = ProbeConfig(
            parallelism = config.probeParallelism,
            restreamOrigins = config.restreamOrigins,
            observationRetention = Duration.ofDays(config.observationRetentionDays.toLong()),
        )
        return ProbeWorker(
            feeds, store, clock, config.ffmpeg, http, probeConfig,
            onObserved = { id -> sessions.healthChanged(id) },
            onHealthChanged = { service?.requestRun("health") },
            targets = service?.let { { it.probeTargets() } },
            relay = loopback,
        )
    }

    private fun dvr(): Dvr = Dvr(
        store, lineup, guides,
        Recorder(config.ffmpeg, sessions, feeds, clock, loopback, shared = relay::joinShared),
        config.recordingsDirectory, clock, { settings.current.maxSessions },
        { Duration.ofDays(settings.current.guideDays.toLong()) },
        retention = { settings.current.let { Retention(it.recordingsFreeGib, it.recordingsKeepDays) } },
        commercials = config.comskip?.let { Commercials(it, config.dataDirectory.resolve("dvr")) },
        jobs = jobs,
    )

    private fun libraries(): LibraryService = LibraryService(
        store, clock,
        listOf(MediaRoot("Media", config.mediaDirectory), MediaRoot("Recordings", config.recordingsDirectory)),
        LibraryScanner(MediaProbe(ffprobeBeside(config.ffmpeg))::probe), jobs,
        metadata = config.tmdb?.let { LibraryMetadata(store, TmdbClient(http, it), clock, it.region) },
        artwork = LibraryArtwork(http, config.dataDirectory.resolve("artwork"), ffmpeg = config.ffmpeg),
        intros = IntroDetector(
            store,
            clock,
            jobs,
            FfmpegFingerprinter(config.ffmpeg, config.dataDirectory.resolve("intros")),
        ).also { it.start(scope) },
        scrub = ScrubSheets(store, clock, jobs, FfmpegSheetMaker(config.ffmpeg), config.dataDirectory.resolve("scrub"))
            .also { it.start(scope) },
        pathMap = config.libraryPathMap,
    ).also { it.start(scope) }

    /**
     * Library playback and the virtual channels' streams, which share `SOFA_MAX_TRANSCODES`: each counts the other's
     * conversions, and the streams are served on the loopback listener.
     */
    private suspend fun playback(dvr: Dvr, libraries: LibraryService): Pair<VodSessions, VirtualStreams> {
        lateinit var streams: VirtualStreams
        val vod = VodSessions(
            libraries.catalog,
            clock,
            config.ffmpeg,
            config.dataDirectory.resolve("transcode"),
            config.maxTranscodes,
            recordings = dvr::playable,
            probe = MediaProbe(ffprobeBeside(config.ffmpeg))::probe,
            encoder = config.videoEncoder,
            others = { streams.running },
        ).also { it.start() }
        streams = VirtualStreams(
            config.ffmpeg,
            config.dataDirectory.resolve("virtual"),
            virtual,
            clock,
            scope,
            vod.encoder,
        ) {
            val converting = vod.snapshot().count { it.mode != Wire.VodMode.DIRECT }
            converting + streams.running < config.maxTranscodes
        }
        loopback.local = streams::serve
        return vod to streams
    }
}
