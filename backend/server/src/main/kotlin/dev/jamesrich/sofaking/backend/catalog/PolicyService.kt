package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.PolicyChangeEntry
import dev.jamesrich.sofaking.backend.api.PolicyDocument
import dev.jamesrich.sofaking.backend.api.PolicyFindings
import dev.jamesrich.sofaking.backend.api.PolicyPreview
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.StationExplanation
import dev.jamesrich.sofaking.backend.jobs.JobOutcome
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.policyChanges
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
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.channels.Channel as Trigger

/** The most logged changes one read returns. */
private const val CHANGES_LIMIT = 1000

/**
 * Builds the lineup from the policy file: runs the [StationBuilder] over the current feeds, guides, health and the
 * remembered availability, writes the lineup when it differs, and writes pass-two numbers back into the policy.
 */
class PolicyService(
    val file: PolicyFile,
    feeds: FeedService,
    guides: GuideService,
    private val lineup: LineupService,
    private val store: BackendStore,
    private val clock: Clock,
    /** The HDHomeRun devices that split the lineup by range; null when they do not. */
    hdhr: HdhrSizing? = null,
    /** Quiet time after a trigger before the run, so a burst of changes is one run. */
    private val debounceMillis: Long = 10_000,
    private val intervalSeconds: Long = 900,
    /** Runs after an apply that changed the lineup. */
    onLineupChanged: () -> Unit = {},
    /** Findings from outside the policy, such as Plex sync, appended to [findings]. */
    private val extraFindings: () -> List<String> = { emptyList() },
    /** Where each run is recorded for the portal's Jobs page. */
    private val jobs: Jobs = Jobs(clock),
) {
    private val log = LoggerFactory.getLogger(PolicyService::class.java)
    private val runMutex = Mutex()
    private val trigger = Trigger<String>(Trigger.CONFLATED)
    private val runner = PolicyRunner(feeds, guides, lineup, store, clock, hdhr)
    private val applier = PolicyApplier(file, feeds, lineup, store, clock, runner, onLineupChanged)

    /** The changes to the policy file a person asks for, each followed by an apply. */
    val edits = PolicyEdits(this, feeds, lineup)

    /** Guide channels proposed by name for stations no guide lists, and the choices a person makes about them. */
    val guideMatches = GuideMatches(this, guides)

    init {
        jobs.register("policy", "Lineup policy run", Duration.ofSeconds(intervalSeconds)) { requestRun("now") }
    }

    /**
     * The offerings the last apply's stations reference, each with its probe tier: 0 for the first source of an
     * unverified station, 1 for its other sources, 2 for the rest. Empty until the first apply.
     */
    fun probeTargets(): Map<String, Int> = applier.probeTargets.get()

    /** The stations and result of the last apply that built the lineup; null until one has. */
    fun lastApplied(): AppliedRun? = applier.lastApplied.get()

    val policy: LineupPolicy get() = file.current().policy

    fun document(): PolicyDocument = file.current().let { PolicyDocument(it.revision, it.path.toString(), it.policy) }

    /**
     * One run over the current snapshot and the remembered availability; writes nothing. Stations whose names match
     * loosely are compared by schedule first, and the aliases that evidence learns are built with.
     */
    suspend fun run(loaded: LoadedPolicy = file.current()): BuildResult = runner.run(loaded)

    suspend fun preview(): PolicyPreview {
        val loaded = file.current()
        val result = run(loaded)
        return previewDiff(result, lineup.entries(), clock.instant(), loaded.revision).copy(
            findings = runner.findingsOf(result),
            learned = result.learned,
            devicesNeeded = runner.devicesNeeded(result),
            devicesConfigured = runner.devicesConfigured,
            unmappedCategories = result.unmappedCategories,
        )
    }

    suspend fun findings(): PolicyFindings {
        val loaded = file.current()
        val result = run(loaded)
        return PolicyFindings(
            clock.instant().toString(),
            loaded.revision,
            runner.findingsOf(result) + extraFindings(),
            result.suggestedAliases,
            result.unmappedCategories,
        )
    }

    suspend fun explain(key: String): StationExplanation {
        val result = run()
        return StationExplanation(
            key,
            result.stations.firstOrNull { it.key == key },
            file.current().policy.stations[key]?.sources.orEmpty(),
            result.candidates.filter { it.key == key || it.station == key },
            result.findings.filter { it.startsWith("$key: ") },
        )
    }

    /** The newest [limit] logged changes, newest first. */
    suspend fun changes(limit: Int): List<PolicyChangeEntry> = store.policyChanges(limit.coerceIn(1, CHANGES_LIMIT))

    /**
     * Builds and writes the lineup in one transaction when it differs, remembers availability, logs each change with
     * [cause], then writes the numbers pass two assigned into the policy so a rebuild needs nothing else.
     */
    suspend fun apply(cause: String): PolicyApplyResult = runMutex.withLock {
        var result: PolicyApplyResult? = null
        jobs.record("policy") {
            val run = applier.apply(cause)
            result = run
            val skipped = run.findings.firstOrNull { it.startsWith("feeds: ") }
            JobOutcome(
                true,
                skipped?.removePrefix("feeds: ")?.let { "skipped: $it" }
                    ?: (
                        "${run.enabledStations} on of ${run.stations}, " +
                            if (run.changed) "${run.changes} fields changed" else "no change"
                        ),
            )
        }
        result!!
    }

    /** Asks the loop to run soon; requests before it runs coalesce into one. */
    fun requestRun(cause: String) {
        trigger.trySend(cause)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // select { onTimeout }
    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("policy-run")) {
        while (isActive) {
            val cause = select<String> {
                trigger.onReceive { it }
                onTimeout(intervalSeconds * 1000) { "interval" }
            }
            // A run the operator asked for starts at once; the rest wait out the debounce so a burst is one run.
            if (cause != "now") delay(debounceMillis)
            val coalesced = trigger.tryReceive().getOrNull()
            runCatchingCancellable {
                apply(coalesced ?: cause)
            }.onFailure { e ->
                log.warn("lineup policy run failed class={} message=\"{}\"", e.javaClass.simpleName, redactedMessage(e))
            }
        }
    }
}

/** The stations one apply built, in lineup order, and what it reported. */
class AppliedRun(val stations: List<PolicyStation>, val result: PolicyApplyResult) {
    /**
     * Guide channels to keep programs for beyond the ones the lineup maps, by guide ID: every candidate of a station
     * whose guide has nothing ahead at [now] by [coverage], so a run can see another guide that does, and the first
     * candidate of a station that moved off it, so a run can move back once it fills again.
     */
    fun wantedGuides(coverage: Map<String, Map<String, Instant>>, now: Instant): Map<String, Set<String>> {
        val wanted = stations.filter { it.enabled }.flatMap { station ->
            val guide = station.guide ?: return@flatMap station.guideCandidates
            val covered = coverage[guide.guideId]?.get(guide.channelId)?.isAfter(now) == true
            when {
                !covered -> station.guideCandidates
                guide != station.guideCandidates.firstOrNull() -> listOfNotNull(station.guideCandidates.firstOrNull())
                else -> emptyList<GuideMapping>()
            }
        }
        return wanted.groupBy({ it.guideId }, { it.channelId }).mapValues { it.value.toSet() }
    }
}

/** HDHomeRun devices that split a policy lineup by range: [devices] of [deviceSize] channels each. */
data class HdhrSizing(val devices: Int, val deviceSize: Int) {
    /** Devices [enabled] channels need. */
    fun needed(enabled: Int): Int = (enabled + deviceSize - 1) / deviceSize
}

/**
 * Each offering [result]'s stations reference, with its probe tier: 0 for the first source of a station no source
 * has verified, 1 for that station's other sources, 2 for every other offering. Stations the policy turns off
 * never rank above 2.
 */
internal fun probeTiers(result: BuildResult): Map<String, Int> {
    val tiers = HashMap<String, Int>()
    for (station in result.stations) {
        val unverified = result.availability[station.key]?.verifiedAt == null && station.disabledReason != "policy"
        station.offeringIds.forEachIndexed { index, id ->
            val tier = when {
                !unverified -> 2
                index == 0 -> 0
                else -> 1
            }
            tiers.merge(id, tier, ::minOf)
        }
    }
    return tiers
}
