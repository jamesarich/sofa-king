package dev.jamesrich.sofaking.backend

import dev.jamesrich.sofaking.backend.ads.AdListener
import dev.jamesrich.sofaking.backend.catalog.CatalogService
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.GuideService
import dev.jamesrich.sofaking.backend.catalog.LineupService
import dev.jamesrich.sofaking.backend.catalog.PolicyService
import dev.jamesrich.sofaking.backend.dvr.Dvr
import dev.jamesrich.sofaking.backend.health.ProbeWorker
import dev.jamesrich.sofaking.backend.http.HdhrAccess
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.library.LibraryService
import dev.jamesrich.sofaking.backend.library.ProgramFactsLookup
import dev.jamesrich.sofaking.backend.library.VodSessions
import dev.jamesrich.sofaking.backend.logos.LogoCache
import dev.jamesrich.sofaking.backend.pairing.PairingService
import dev.jamesrich.sofaking.backend.playback.LoopbackRelay
import dev.jamesrich.sofaking.backend.playback.MediaRelay
import dev.jamesrich.sofaking.backend.playback.SessionManager
import dev.jamesrich.sofaking.backend.playback.TsRemux
import dev.jamesrich.sofaking.backend.plex.PlexSync
import dev.jamesrich.sofaking.backend.settings.RuntimeSettings
import dev.jamesrich.sofaking.backend.settings.RuntimeValues
import dev.jamesrich.sofaking.backend.setup.SetupService
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.update.UpdateChecker
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock

private val runtimeLog = LoggerFactory.getLogger(LiveRuntime::class.java)

/**
 * The runtime's scope: a background coroutine that fails is logged by its name and exception
 * class, and the others keep running.
 */
internal fun backgroundScope(report: (String) -> Unit = runtimeLog::error): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { context, failure ->
        report(
            "background coroutine failed name=${context[CoroutineName]?.name ?: "unnamed"} " +
                "class=${failure.javaClass.simpleName}",
        )
    },
)

/**
 * Runs [action] every [periodMillis] until canceled; a failed run is logged by exception class and the loop goes on.
 */
internal fun CoroutineScope.launchEvery(
    name: String,
    periodMillis: Long,
    report: (String) -> Unit = runtimeLog::warn,
    action: suspend () -> Unit,
): Job = launch(CoroutineName(name)) {
    while (isActive) {
        runCatchingCancellable { action() }.onFailure { report("$name failed class=${it.javaClass.simpleName}") }
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
    /**
     * The published-image check and the hand-off to the host's updater; null when the
     * runtime was built without a config.
     */
    val updates: UpdateChecker? = null,
    /** Setup from the portal and the API: the source file, the starter policy and the restart that reads them. */
    val setup: SetupService? = null,
    /** The media libraries; null for a runtime built without a config. */
    val libraries: LibraryService? = null,
    /** Library files being played; null without libraries. */
    val vod: VodSessions? = null,
    /** The DVR; null when the runtime was built without a config. */
    val dvr: Dvr? = null,
    /** The loopback relay the remux, the recorder and the probes' FFmpeg read sources through. */
    val loopback: LoopbackRelay = LoopbackRelay(http, feeds.upstream, relay.permits),
    /**
     * Who may reach the HDHomeRun emulation; the default admits everyone, as a loopback bind with no allow-list does.
     */
    val hdhrAccess: HdhrAccess = HdhrAccess(emptyList(), openWhenEmpty = true, store, Clock.systemUTC()),
    /** The background ad listener, for the portal's Ads page; null for a runtime built without a config. */
    val listener: AdListener? = null,
    /** TMDB's facts about live programs, for the break screen; null without a TMDB token. */
    val programFacts: ProgramFactsLookup? = null,
) {
    suspend fun close() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        sessions.close()
        vod?.close()
        remux.close()
        loopback.close()
        http.close()
    }
    companion object {
        /**
         * [probes] false keeps the probe worker from starting, for tests over fixtures; [restart] is what a
         * setup write ends the process with.
         */
        suspend fun create(
            store: BackendStore,
            config: ServerConfig,
            clock: Clock,
            policyDebounceMillis: Long = 10_000,
            probes: Boolean = true,
            plexDebounceMillis: Long = 120_000,
            restart: () -> Unit = SetupService::exitForRestart,
        ): LiveRuntime {
            val started = clock.instant()
            val assembly =
                RuntimeAssembly(
                    store,
                    config,
                    clock,
                    RuntimeOptions(policyDebounceMillis, probes, plexDebounceMillis, restart),
                )
            // Whatever fails before the runtime is whole stops its background work and closes its client.
            var created = false
            try {
                return assembly.assemble(started).also { created = true }
            } finally {
                if (!created) {
                    assembly.scope.cancel()
                    assembly.http.close()
                }
            }
        }
    }
}
