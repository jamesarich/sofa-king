package dev.jamesrich.sofaking.backend.health

import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.io.runFor
import dev.jamesrich.sofaking.backend.playback.LoopbackRelay
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.backend.store.markProbeAttempt
import dev.jamesrich.sofaking.backend.store.probeAttempts
import dev.jamesrich.sofaking.backend.store.pruneObservations
import dev.jamesrich.sofaking.backend.store.recordObservation
import dev.jamesrich.sofaking.backend.upstream.FfmpegSource
import dev.jamesrich.sofaking.backend.upstream.ffmpegBase
import dev.jamesrich.sofaking.backend.upstream.ffmpegProcess
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

data class ProbeConfig(
    val parallelism: Int = 6,
    val restreamOrigins: Set<String> = emptySet(),
    val httpTimeoutMillis: Long = 8000,
    val decodeSeconds: Int = 30,
    val deadlineSeconds: Long = 50,
    val observationRetention: Duration = OBSERVATION_RETENTION,
)

/**
 * Probes run on [ProbeConfig.parallelism] workers that claim due offerings from one shared queue.
 * Suspect, probation and quarantined sources are claimed before unverified ones, then healthy rechecks; under a
 * lineup policy the policy's tiers rank first. Persistent attempt times keep the five-minute spacing across restarts.
 */
class ProbeWorker(
    private val feeds: FeedService,
    private val store: BackendStore,
    private val clock: Clock,
    private val ffmpeg: String,
    http: HttpClient? = null,
    private val config: ProbeConfig = ProbeConfig(),
    /** Fires only when an observation changes the offering's status. */
    private val onHealthChanged: suspend (String) -> Unit = {},
    /** Fires after every observation, changed or not. */
    private val onObserved: suspend (String) -> Unit = {},
    /**
     * Under a lineup policy: the offerings to probe, whatever their provider's `probe` flag, each with a tier that
     * ranks before health status (lower first). Offerings it leaves out are never probed. Null without a policy.
     */
    private val targets: (() -> Map<String, Int>)? = null,
    /**
     * The relay the decode tier's FFmpeg reads sources through; without one, the decode tier
     * fails as [FailureClass.LOCAL].
     */
    private val relay: LoopbackRelay? = null,
    /** Where a source going into quarantine or recovering from it is published; none without an event stream. */
    private val events: ServerEvents? = null,
    private val qualifier: (suspend (Offering, Map<String, String>) -> FailureClass?)? = null,
) {
    private val log = LoggerFactory.getLogger(ProbeWorker::class.java)
    private val fastTier = http?.let {
        HttpQualifier(it, config.restreamOrigins, config.httpTimeoutMillis, feeds.upstream)
    }
    private val mutex = Mutex()
    private val pending = ArrayDeque<Offering>()
    private val inFlight = HashSet<String>()
    private val outcomes = HashMap<String, Int>()
    private var batchStartedAt: Instant? = null

    /** When a source of each provider last qualified, and when each source was last refused, for [evidence]. */
    private val providerQualified = HashMap<String, Instant>()
    private val refused = HashMap<String, Instant>()

    suspend fun run() = coroutineScope {
        repeat(config.parallelism) { launch { worker() } }
    }

    private suspend fun worker() {
        var idle = IDLE_MS.first
        while (currentCoroutineContext().isActive) {
            val worked =
                runCatchingCancellable {
                    step()
                }.getOrElse { e ->
                    // Never print media URLs: exception messages can carry signed query tokens.
                    log.warn("probe failed class={}", e.javaClass.simpleName)
                    false
                }
            if (worked) {
                idle = IDLE_MS.first
            } else {
                delay(idle)
                idle = (idle * 2).coerceAtMost(IDLE_MS.last)
            }
        }
    }

    /**
     * Puts [offeringId] at the front of the queue, so the next free worker probes it whether or not it is due. False
     * when no provider lists the offering or a probe of it is already in flight.
     */
    suspend fun probeNow(offeringId: String): Boolean {
        val offering = feeds.published.offering(offeringId) ?: return false
        return mutex.withLock {
            if (offeringId in inFlight) return@withLock false
            pending.removeAll { it.id == offeringId }
            pending.addFirst(offering)
            true
        }
    }

    /** Probes one claimed offering; false when nothing is due. */
    suspend fun step(): Boolean {
        val offering = claim() ?: return false
        try {
            probe(offering)
        } finally {
            mutex.withLock { inFlight -= offering.id }
        }
        return true
    }

    private suspend fun claim(): Offering? = mutex.withLock {
        if (pending.isEmpty()) {
            val now = clock.instant()
            batchStartedAt?.let { started ->
                log.info(
                    "probe batch done outcomes={} durationMs={}",
                    outcomes.toSortedMap(),
                    Duration.between(started, now).toMillis(),
                )
            }
            outcomes.clear()
            pending += due(now).filter { it.id !in inFlight }
            batchStartedAt = if (pending.isEmpty()) null else now
            val pruned = store.pruneObservations(now.minus(config.observationRetention))
            if (pruned > 0) {
                log.info(
                    "pruned health observations count={} retentionDays={}",
                    pruned,
                    config.observationRetention.toDays(),
                )
            }
        }
        val next = pending.removeFirstOrNull() ?: return@withLock null
        // Recorded before it counts as in flight: a failed write must not leave it unprobeable until a restart.
        store.markProbeAttempt(next.id, clock.instant())
        inFlight += next.id
        next
    }

    private suspend fun due(now: Instant): List<Offering> {
        val states = store.allHealth()
        val attempts = store.probeAttempts()
        val plan = targets?.invoke()
        return feeds
            .published.offerings
            .asSequence()
            .filter {
                val feed = feeds.config(it.providerId)
                if (plan != null) it.id in plan else feed.probe
            }.mapNotNull { offering ->
                val state = states[offering.id] ?: return@mapNotNull null
                val probeable =
                    !state.manuallyDisabled && state.status != HealthStatus.ARCHIVED && state.probeDue(now) &&
                        attempts[offering.id]?.let { now < it.plusSeconds(300) } != true
                if (probeable) offering to state else null
            }.sortedWith(
                compareBy({ plan?.get(it.first.id) ?: 0 }, { it.second.status.probePriority() }, {
                    attempts[it.first.id]
                        ?: Instant.MIN
                }),
            )
            .take(config.parallelism * CLAIMS_PER_WORKER)
            .map { it.first }
            .toList()
    }

    private suspend fun probe(offering: Offering) {
        val before = store.health(offering.id)?.status
        // A native source is probed at the URL its service resolves; a service that cannot answer is provider evidence.
        var resolveFailure: FailureClass? = null
        val stream: ResolvedStream? = runCatchingCancellable { feeds.playable(offering) }.getOrElse { e ->
            resolveFailure = if (e is ProviderFailure) {
                if (e.transient) FailureClass.PROVIDER else FailureClass.OFFERING
            } else {
                // Recorded as the source's own failure, so it backs off rather than being claimed again unrecorded.
                log.warn("probe resolve failed offering={} class={}", offering.id, e.javaClass.simpleName)
                FailureClass.OFFERING
            }
            null
        }
        val target = stream?.let { offering.copy(url = it.url) } ?: offering
        val headers = stream?.headers.orEmpty()
        val failure =
            if (resolveFailure != null) {
                resolveFailure
            } else if (qualifier != null) {
                qualifier.invoke(target, headers)
            } else if (fastTier == null) {
                decode(target, headers, recheck = before == HealthStatus.HEALTHY)
            } else {
                val qualification = fastTier.check(target, headers)
                val protocol = qualification.protocol
                // The body is the authority on the protocol; the decode tier's reconnect flags depend on it too.
                if (protocol != null && protocol != offering.protocol) feeds.recordProtocol(offering.id, protocol)
                qualification.failure
                    ?: decode(
                        if (protocol == null) {
                            target
                        } else {
                            target.copy(protocol = protocol)
                        },
                        headers,
                        recheck =
                        before == HealthStatus.HEALTHY,
                    )
            }
        record(offering, failure, before)
    }

    /** Records the probe's [failure] (null when it qualified) as evidence, and tells the callbacks. */
    private suspend fun record(offering: Offering, failure: FailureClass?, before: HealthStatus?) {
        val at = clock.instant()
        val recorded = mutex.withLock {
            evidence(offering, failure, at).also { outcomes.merge(it?.name ?: "QUALIFIED", 1, Int::plus) }
        }
        val after = store.recordObservation(
            offering.id,
            Observation(UUID.randomUUID().toString(), at, recorded == null, recorded),
            at,
        )
        onObserved(offering.id)
        if (before != after.status) {
            log.info("source health offering={} from={} to={}", offering.id, before, after.status)
            events.healthChanged(offering, feeds.published.channel(offering.channelId)?.name, before, after.status)
            onHealthChanged(offering.id)
        }
    }

    /**
     * [failure] as the health policy takes it. A refusal ([FailureClass.AUTH]) is the provider's while its credentials
     * fail everywhere, but this source's own once another source of the provider has qualified since this one was
     * last refused, as with a channel geo-blocked or behind a partner's dead token: it then strikes as
     * [FailureClass.OFFERING]. A credential outage stops the provider's other sources qualifying too, so it never
     * quarantines them.
     */
    private fun evidence(offering: Offering, failure: FailureClass?, at: Instant): FailureClass? {
        if (failure == null) providerQualified[offering.providerId] = at
        if (failure != FailureClass.AUTH) return failure
        val previous = refused.put(offering.id, at)
        val qualified = providerQualified[offering.providerId]
        val sourceOwn = previous != null && qualified != null && !qualified.isBefore(previous)
        return if (sourceOwn) FailureClass.OFFERING else failure
    }

    /**
     * Decode tier: reconnects across upstream EOF so a hiccuping source still delivers its 30 seconds. A [recheck] of
     * a healthy source copies the video packets instead of decoding them: it proves the stream still delivers at a
     * fraction of the CPU, and a source that fails it is fully decoded again once it is no longer healthy. FFmpeg
     * reads [offering] through [relay], which sends [headers] and checks every hop.
     */
    internal suspend fun decode(
        offering: Offering,
        headers: Map<String, String>,
        recheck: Boolean = false,
    ): FailureClass? {
        val relay = relay ?: return FailureClass.LOCAL
        return relay.reading(ResolvedStream(offering.url, headers), offering.protocol) { source ->
            decode(source, offering.protocol, recheck)
        }
    }

    private suspend fun decode(source: FfmpegSource, protocol: String, recheck: Boolean): FailureClass? =
        withContext(Dispatchers.IO) {
            val output = Files.createTempFile("sofa-probe-", ".progress")
            val errors = Files.createTempFile("sofa-probe-", ".errors")
            try {
                val command = decodeCommand(ffmpeg, source, protocol, config.decodeSeconds, recheck)
                val exit = ffmpegProcess(
                    command,
                ).redirectOutput(output.toFile()).redirectError(errors.toFile()).runFor(config.deadlineSeconds.seconds)
                if (exit == null) {
                    FailureClass.PROVIDER
                } else {
                    val progress = Files.readString(output)
                    val frames = FRAME_LINE.findAll(progress).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val micros = OUT_TIME_LINE.findAll(progress).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    // FFmpeg 6 reports no frame count when copying, so a recheck stands on the output time alone.
                    val enoughFrames = recheck || frames >= config.decodeSeconds
                    if (exit == 0 && enoughFrames && micros >= TimeUnit.SECONDS.toMicros(config.decodeSeconds - 1L)) {
                        null
                    } else {
                        classifyFailure(
                            Files.newInputStream(errors).use {
                                it.readNBytes(ERROR_BYTES).toString(Charsets.UTF_8)
                            },
                        )
                    }
                }
            } catch (_: java.io.IOException) {
                FailureClass.LOCAL
            } finally {
                Files.deleteIfExists(output)
                Files.deleteIfExists(errors)
            }
        }
}

/**
 * Which offerings a step probes first, after the plan's order: the unhealthy, then the unverified, then the healthy.
 */
private fun HealthStatus.probePriority(): Int = when (this) {
    HealthStatus.HEALTHY -> 2
    HealthStatus.UNVERIFIED -> 1
    else -> 0
}

/** What FFmpeg's [error] output says failed. */
internal fun classifyFailure(error: String): FailureClass = when {
    AUTH_ERROR.containsMatchIn(error) -> FailureClass.AUTH
    RATE_LIMIT_ERROR.containsMatchIn(error) -> FailureClass.RATE_LIMIT
    PROVIDER_ERROR.containsMatchIn(error) -> FailureClass.PROVIDER
    LOCAL_ERROR.containsMatchIn(error) -> FailureClass.LOCAL
    else -> FailureClass.OFFERING
}

/** Whether this offering is due a probe at [now]: at its scheduled check, else when its last observation is old. */
private fun HealthState.probeDue(now: Instant): Boolean {
    if (nextCheckAt != null) return now >= nextCheckAt
    val last = lastObservationAt ?: return true
    return now >=
        last.plus(if (status == HealthStatus.HEALTHY) HEALTHY_EVERY else UNHEALTHY_EVERY)
}

/** How long the observations behind an offering's health are kept. */
private val OBSERVATION_RETENTION: Duration = Duration.ofDays(7)

/** A worker with nothing due waits the shorter time, doubling it up to the longer while nothing comes due. */
private val IDLE_MS = 1_000L..30_000L

/** How many due offerings one step looks at for each worker. */
private const val CLAIMS_PER_WORKER = 4

/** How often an offering without a scheduled check is probed again, by whether it is healthy. */
private val HEALTHY_EVERY: Duration = Duration.ofHours(6)
private val UNHEALTHY_EVERY: Duration = Duration.ofMinutes(5)

/** How much of FFmpeg's error output a failed decode is classified by. */
private const val ERROR_BYTES = 8_192

/** FFmpeg error lines, by the failure they show. */
private val AUTH_ERROR = Regex("(?i)(HTTP error |server returned )(401|403)\\b")
private val RATE_LIMIT_ERROR = Regex("(?i)(HTTP error |server returned )429\\b")
private val PROVIDER_ERROR =
    Regex(
        "(?i)HTTP error 5[0-9]{2}|server returned 5(XX|[0-9]{2})|connection refused|failed to resolve|network is " +
            "unreachable|connection timed out|connection reset|broken pipe|TLS handshake",
    )
private val LOCAL_ERROR =
    Regex(
        "(?i)decoder .*not found|unknown decoder|permission denied|no space left|cannot allocate memory|resource " +
            "temporarily unavailable|protocol not found",
    )

/** Lines of FFmpeg's `-progress` output. */
private val FRAME_LINE = Regex("(?m)^frame=(\\d+)$")
private val OUT_TIME_LINE = Regex("(?m)^out_time_us=(\\d+)$")

/**
 * The FFmpeg command that reads [seconds] of [source]'s first video stream, decoding it or, with [copy], copying it.
 */
internal fun decodeCommand(
    ffmpeg: String,
    source: FfmpegSource,
    protocol: String,
    seconds: Int,
    copy: Boolean,
): List<String> {
    val command = ffmpegBase(ffmpeg, "error", source)
    // The HLS demuxer handles segment boundaries itself; reconnect-at-EOF there loops on the first segment.
    if (protocol == "mpegts") {
        command +=
            listOf("-reconnect", "1", "-reconnect_at_eof", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5")
    }
    command += listOf("-i", source.url, "-t", seconds.toString(), "-map", "0:v:0")
    if (copy) command += listOf("-c", "copy")
    command += listOf("-an", "-progress", "pipe:1", "-f", "null", "-")
    return command
}

/** Publishes a source entering quarantine, or coming back healthy from it or from probation. */
private fun ServerEvents?.healthChanged(
    offering: Offering,
    channelName: String?,
    before: HealthStatus?,
    after: HealthStatus,
) {
    val type = when {
        after == HealthStatus.QUARANTINED -> EventType.SOURCE_QUARANTINED

        after == HealthStatus.HEALTHY && (before == HealthStatus.QUARANTINED || before == HealthStatus.PROBATION) ->
            EventType.SOURCE_RECOVERED

        else -> return
    }
    this?.publish(
        type,
        buildJsonObject {
            put("offeringId", offering.id)
            put("providerId", offering.providerId)
            put("channelId", offering.channelId)
            put("channelName", channelName)
        },
    )
}
