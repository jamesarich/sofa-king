package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.backend.store.stationAvailability
import java.time.Clock
import java.time.Instant

/** Runs the [StationBuilder] over the current feeds, guides, health and remembered availability; writes nothing. */
internal class PolicyRunner(
    private val feeds: FeedService,
    private val guides: GuideService,
    private val lineup: LineupService,
    private val store: BackendStore,
    private val clock: Clock,
    private val hdhr: HdhrSizing?,
) {
    /**
     * One run over the current snapshot. Stations whose names match loosely are compared by schedule first, and the
     * aliases that evidence learns are built with.
     */
    suspend fun run(loaded: LoadedPolicy): BuildResult {
        val candidates = feeds.published.channels().map { channel ->
            FeedCandidate(
                channel,
                feeds.published.providerOf(channel.id).orEmpty(),
                feeds.published.offeringsOf(channel.id),
            )
        }
        val providerIds = feeds.configs.mapTo(HashSet()) { it.id }
        val health = store.allHealth()
        val previous = store.stationAvailability()
        val now = clock.instant()
        // A first policy run counts the lineup it replaces as verified, so turning a policy on never
        // hides channels already watched.
        val seed = if (previous.isEmpty()) {
            lineup.entries().filter { it.enabled && it.createdBy != "policy" }.flatMapTo(HashSet()) { it.offeringIds }
        } else {
            emptySet()
        }
        val coverage = guides.coverage
        val covered = { mapping: GuideMapping ->
            coverage[mapping.guideId]?.get(mapping.channelId)?.isAfter(now) == true
        }
        fun build(policy: LineupPolicy) = StationBuilder.build(
            BuildInput(policy, candidates, guides::guidesListing, providerIds, health, previous, now, seed, covered),
        )
        val first = build(loaded.policy)
        val learning = learnAliases(first, loaded.policy, schedules(first, loaded.policy, now))
        return if (learning.learned.isEmpty()) {
            first.with(learning)
        } else {
            build(withLearned(loaded.policy, learning.learned)).with(learning)
        }
    }

    /** The run's findings, with one about the HDHomeRun devices when the enabled stations need more than there are. */
    fun findingsOf(result: BuildResult): List<String> {
        val sizing = hdhr ?: return result.findings
        val enabled = enabledChannels(result)
        val needed = sizing.needed(enabled)
        if (needed <= sizing.devices) return result.findings
        val finding = "hdhr: $enabled enabled stations need $needed devices of ${sizing.deviceSize} channels, " +
            "${sizing.devices} configured; add ports to SOFA_HDHR_EXTRA_PORTS"
        return (result.findings + finding).sorted()
    }

    /** The HDHomeRun devices configured; null when no devices split the lineup. */
    val devicesConfigured: Int? = hdhr?.devices

    /** The HDHomeRun devices the run's enabled stations need; null when no devices split the lineup. */
    fun devicesNeeded(result: BuildResult): Int? = hdhr?.needed(enabledChannels(result))

    /** Enabled channels the devices serve: one per number. */
    private fun enabledChannels(result: BuildResult): Int =
        result.stations.filter { it.enabled }.distinctBy { it.number }.size

    /** The programs in the learning window of every station in a loose pair that has a guide, by station key. */
    private suspend fun schedules(
        result: BuildResult,
        policy: LineupPolicy,
        now: Instant,
    ): Map<String, List<GuideProgram>> {
        val keys = loosePairs(result, policy.names.never).flatMap { listOf(it.first, it.second) }.toSet()
        val mapped = result.stations.filter { it.key in keys && it.guide != null }.associate { it.key to it.guide!! }
        val byGuide = mapped.values.groupBy({ it.guideId }, { it.channelId })
        val programs = byGuide.mapValues { (guideId, channels) ->
            guides.programs(guideId, channels.toSet(), now, now.plus(LEARN_WINDOW))
        }
        return mapped.mapValues { (_, guide) -> programs[guide.guideId]?.get(guide.channelId).orEmpty() }
    }
}
