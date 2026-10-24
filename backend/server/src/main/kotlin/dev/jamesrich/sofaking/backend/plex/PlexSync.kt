package dev.jamesrich.sofaking.backend.plex

import dev.jamesrich.sofaking.backend.PlexConfig
import dev.jamesrich.sofaking.backend.api.PlexDeviceStatus
import dev.jamesrich.sofaking.backend.api.PlexStatus
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.add
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel as Trigger

/** One of this server's HDHomeRun devices: its index, the DeviceID it reports, and the port Plex reaches it on. */
data class HdhrDevice(val index: Int, val id: String, val port: Int)

/** Plex rejects a longer channel-map request line; 35 KB fails where 25 KB saves. */
internal const val PLEX_URL_LIMIT = 30_000

/** How much of a failed sync's error the Jobs page shows, and how much the status keeps. */
private const val ERROR_SHOWN = 200
private const val ERROR_LIMIT = 1_000

/**
 * Keeps Plex Live TV's channel maps in step with the lineup: for each DVR device that is one of this server's tuners,
 * reads the channel map Plex proposes and saves it with every channel enabled, then reloads that DVR's guide. With
 * [PlexConfig.tunerBase] set it first adds devices no DVR holds to the first DVR that holds one of ours.
 */
class PlexSync(
    private val config: PlexConfig,
    private val http: HttpClient,
    private val clock: Clock,
    private val devices: suspend () -> List<HdhrDevice>,
    /** Quiet time after a lineup change before the sync, so a burst of changes is one sync. */
    private val debounceMillis: Long = 120_000,
    intervalMillis: Long = TimeUnit.MINUTES.toMillis(config.syncMinutes.toLong()),
    /** The status a previous run stored, shown until this run's first sync replaces it. */
    initial: PlexStatus? = null,
    /** Stores each sync's status; a failure is logged and the sync still counts. */
    private val persist: suspend (PlexStatus) -> Unit = {},
    /** Where each sync is recorded for the portal's Jobs page. */
    private val jobs: Jobs = Jobs(clock),
) {
    private val log = LoggerFactory.getLogger(PlexSync::class.java)
    private val mutex = Mutex()
    private val trigger = Trigger<String>(Trigger.CONFLATED)
    private val api = PlexApi(config, http)

    @Volatile private var intervalMillis: Long = intervalMillis
    private val syncMinutes: Int get() = (intervalMillis / 60_000L).toInt()
    private val status = AtomicReference(initial?.copy(syncMinutes = syncMinutes) ?: PlexStatus(syncMinutes))

    init {
        register()
    }

    private fun register() {
        jobs.register("plex", "Plex sync", Duration.ofMillis(intervalMillis)) { requestSync("now") }
    }

    /** Syncs every [minutes] from the next sync on; the status and the Jobs page follow. */
    fun setInterval(minutes: Int) {
        val millis = TimeUnit.MINUTES.toMillis(minutes.toLong())
        if (millis == intervalMillis) return
        intervalMillis = millis
        register()
        status.updateAndGet { it.copy(syncMinutes = minutes) }
    }

    fun status(): PlexStatus = status.get()

    /** Findings of the last sync, for the policy findings. */
    fun findings(): List<String> = status.get().findings

    /** Asks the loop to sync after the debounce; requests before it runs coalesce into one. */
    fun requestSync(cause: String) {
        trigger.trySend(cause)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // select { onTimeout }
    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("plex-sync")) {
        while (isActive) {
            val cause = select<String> {
                trigger.onReceive { it }
                onTimeout(intervalMillis) { "interval" }
            }
            // A sync the operator asked for starts at once; a lineup change waits out the
            // debounce so a burst is one sync.
            if (cause != "interval" && cause != "now") {
                delay(debounceMillis)
                trigger.tryReceive()
            }
            runCatchingCancellable {
                sync(cause)
            }.onFailure { e ->
                log.warn("plex sync failed class={} message=\"{}\"", e.javaClass.simpleName, api.message(e))
            }
        }
    }

    /** One sync now; failures are recorded in the status, never thrown. */
    suspend fun sync(cause: String): PlexStatus = mutex.withLock {
        var next: PlexStatus? = null
        jobs.record("plex") {
            val status = syncNow(cause)
            next = status
            if (status.lastError != null) {
                JobOutcome(false, status.lastError.take(ERROR_SHOWN))
            } else {
                JobOutcome(
                    true,
                    "${status.devices.sumOf { it.mapped }} channels mapped on ${status.devices.size} devices" +
                        if (status.missingDevices.isEmpty()) "" else ", ${status.missingDevices.size} not in Plex",
                )
            }
        }
        next!!
    }

    private suspend fun syncNow(cause: String): PlexStatus {
        val started = clock.instant()
        val pass = SyncPass(api, devices(), addDevices = config.tunerBase != null)
        pass.run()
        val error = pass.error
        val next = PlexStatus(
            syncMinutes,
            clock.instant().toString(),
            cause,
            error,
            pass.touched.toList(),
            pass.mapped.sortedBy { it.device },
            pass.missing.map { it.id },
            pass.findings,
        )
        status.set(next)
        runCatchingCancellable {
            persist(next)
        }.onFailure { e ->
            log.warn("plex status not stored class={}", e.javaClass.simpleName)
        }
        if (error != null) log.warn("plex sync cause={} error=\"{}\"", cause, error)
        log.info(
            "plex sync cause={} dvrs={} devices={} mapped={} missing={} durationMs={}",
            cause,
            pass.touched.size,
            pass.mapped.size,
            pass.mapped.sumOf { it.mapped },
            pass.missing.size,
            Duration.between(started, clock.instant()).toMillis(),
        )
        return next
    }
}

/** One of our devices where Plex holds it: in [dvr] as [device]. */
private class Present(val dvr: Dvr, val device: Device, val mine: HdhrDevice)

/**
 * One sync of [ours] through [plex]: finds which DVR holds each device, adds the absent ones when [addDevices], saves
 * each held device's channel map and reloads the guides it changed. Each step's failure is kept and the rest go on.
 */
private class SyncPass(private val plex: PlexApi, private val ours: List<HdhrDevice>, private val addDevices: Boolean) {
    val errors = ArrayList<String>()
    val findings = ArrayList<String>()
    val mapped = ArrayList<PlexDeviceStatus>()
    val touched = LinkedHashSet<String>()
    var missing = emptyList<HdhrDevice>()
        private set

    /** The errors joined for the status, or null when the sync had none. */
    val error: String? get() = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(ERROR_LIMIT)

    suspend fun run() {
        runCatchingCancellable {
            val present = plex.dvrs().flatMap { dvr ->
                dvr.devices.mapNotNull { device ->
                    ours.firstOrNull { device.uuid.endsWith("/${it.id}", ignoreCase = true) }?.let {
                        Present(dvr, device, it)
                    }
                }
            }.toMutableList()
            val added = addAbsent(present)
            for (place in present) map(place, place.mine in added)
            for (dvr in touched) attempt("DVR $dvr") { plex.reloadGuide(dvr) }
        }.onFailure { e ->
            errors += plex.message(e)
        }
        error?.let { findings += "plex: sync failed: $it" }
    }

    /** Adds the devices no DVR holds to the first DVR that holds one of ours, when asked to; returns those added. */
    private suspend fun addAbsent(present: MutableList<Present>): Set<HdhrDevice> {
        val absent = ours.filter { mine -> present.none { it.mine == mine } }
        val added = HashSet<HdhrDevice>()
        val target = present.firstOrNull()?.dvr
        if (addDevices && target != null) {
            for (device in absent) {
                attempt("device ${device.id}") {
                    present += Present(target, plex.add(target, device), device)
                    added += device
                }
            }
        }
        missing = absent - added
        for (device in missing) findings += "plex: device ${device.id} (device ${device.index}) is not added to Plex"
        return added
    }

    private suspend fun map(place: Present, added: Boolean) {
        val mine = place.mine
        attempt("device ${mine.id}") {
            val count = plex.map(place.dvr, place.device, mine, findings)
            if (count > 0) touched += place.dvr.key
            mapped += PlexDeviceStatus(mine.id, mine.index, place.device.key, place.dvr.key, count, added)
        }
    }

    /** Runs [step], keeping its failure as an error about [what]. */
    private suspend fun attempt(what: String, step: suspend () -> Unit) {
        runCatchingCancellable { step() }.onFailure { e -> errors += "$what: ${plex.message(e)}" }
    }
}
