package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.recordPolicyChanges
import dev.jamesrich.sofaking.backend.store.saveStationAvailability
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds and writes the lineup in one transaction when it differs, remembers availability, logs each change, then
 * writes the numbers pass two assigned into the policy so a rebuild needs nothing else.
 */
internal class PolicyApplier(
    private val file: PolicyFile,
    private val feeds: FeedService,
    private val lineup: LineupService,
    private val store: BackendStore,
    private val clock: Clock,
    private val runner: PolicyRunner,
    private val onLineupChanged: () -> Unit,
) {
    private val log = LoggerFactory.getLogger(PolicyService::class.java)

    /** The offerings of the last apply's stations with their probe tiers, as [probeTiers] gives them. */
    val probeTargets = AtomicReference<Map<String, Int>>(emptyMap())

    /** The stations and result of the last apply that built the lineup. */
    val lastApplied = AtomicReference<AppliedRun?>(null)

    suspend fun apply(cause: String): PolicyApplyResult {
        val started = clock.instant()
        val loaded = file.current()
        skipped(started, loaded)?.let { return it }
        val result = runner.run(loaded)
        probeTargets.set(probeTiers(result))
        val findings = runner.findingsOf(result).toMutableList()
        val current = lineup.entries()
        val built = built(result, current, findings)
        val changes = changesBetween(current, built.entries, built.keyById, built.reasonById, result.keys, cause)
        val changed = built.entries != current
        if (changed) {
            lineup.mutate { entries ->
                entries.clear()
                entries.addAll(built.entries)
            }
            onLineupChanged()
        }
        val now = clock.instant()
        store.saveStationAvailability(result.availability, now)
        store.recordPolicyChanges(changes, now)
        val numbers = Numbers(loaded, result)
        val revision = writeBack(loaded, result, numbers, findings)
        for ((from, to) in result.learned) log.info("lineup policy learned alias from={} to={}", from, to)
        log.info(
            "lineup policy run cause={} stations={} changed={} changes={} assigned={} learned={} findings={} " +
                "durationMs={}",
            cause, built.entries.size, changed, changes.size, numbers.all.size, result.learned.size, findings.size,
            Duration.between(started, clock.instant()).toMillis(),
        )
        return PolicyApplyResult(
            now.toString(), revision, changed, built.entries.size, built.entries.count { it.enabled },
            changes.size, numbers.all.toSortedMap(), findings.sorted(), result.suggestedAliases,
            result.learned, runner.devicesNeeded(result), runner.devicesConfigured, result.unmappedCategories,
        ).also { lastApplied.set(AppliedRun(result.stations, it)) }
    }

    /**
     * The result of a run that builds nothing, while a feed waits for its first import or none has a live channel:
     * numbers assigned now are written into the policy for good, so a partial catalog must never be built from.
     */
    private fun skipped(started: Instant, loaded: LoadedPolicy): PolicyApplyResult? {
        val waiting = feeds.statuses.filter { it.lastSuccessAt == null && it.errorCode == null }.map { it.id }.sorted()
        val reason = when {
            waiting.isNotEmpty() -> "waiting for the first import of ${waiting.joinToString()}"

            feeds.published.channels().none { feeds.published.offeringsOf(it.id).isNotEmpty() } ->
                "no feed has a live channel yet"

            else -> return null
        }
        log.info("lineup policy run skipped: {}", reason)
        val entries = lineup.entries()
        return PolicyApplyResult(
            started.toString(),
            loaded.revision,
            false,
            entries.size,
            entries.count { it.enabled },
            findings = listOf("feeds: $reason; the lineup is left as it is"),
        )
    }

    private class Built(
        val entries: List<LineupEntryRecord>,
        val keyById: Map<String, String>,
        val reasonById: Map<String, String?>,
    )

    /**
     * The lineup [result] builds, one entry per station ID in station order. A station whose entry fails validation
     * keeps its entry in [current], if it has one, and adds a finding.
     */
    private fun built(result: BuildResult, current: List<LineupEntryRecord>, findings: MutableList<String>): Built {
        val currentById = current.associateBy { it.id }
        val stations = result.stations.distinctBy { it.id }
        val entries = stations.mapNotNull { station ->
            try {
                lineup.drafts.validated(record(station, result))
            } catch (e: ApiFailure) {
                findings += "${station.key}: left as it was: ${e.message}"
                currentById[station.id]
            }
        }
        return Built(
            entries,
            stations.associate { it.id to it.key },
            stations.associate { it.id to it.disabledReason },
        )
    }

    private fun record(station: PolicyStation, result: BuildResult): LineupEntryRecord {
        val unavailable = station.disabledReason == "unavailable"
        return LineupEntryRecord(
            station.id, station.number, station.name, station.group, station.logo, station.enabled, station.guide,
            station.offeringIds,
            createdBy = "policy",
            autoDisabledAt = if (unavailable) result.availability[station.key]?.unavailableSince else null,
            autoDisabledReason = if (unavailable) "unavailable" else null,
        )
    }

    /** What pass two numbered that the policy file does not hold yet. */
    private class Numbers(loaded: LoadedPolicy, result: BuildResult) {
        /** Server-numbered stations that moved into their group's block, key to old and new number. */
        val moved = result.renumbered.mapNotNull { (key, number) ->
            loaded.policy.stations[key]?.number?.let { key to (it to number) }
        }.toMap()

        /** Every number to write, key to number: the new ones and the moved ones. */
        val all = result.assigned.filterKeys { loaded.policy.stations[it]?.number == null } +
            moved.mapValues { it.value.second }

        /** Server-numbered keys whose numbers go back. */
        val reclaimed = result.reclaimed.filterTo(HashSet()) { it in loaded.policy.numbering.assigned }
    }

    /**
     * Writes [numbers] and the learned aliases into the policy file; the revision after, else [loaded]'s when there
     * is nothing to write or the write fails, which adds a finding.
     */
    private suspend fun writeBack(
        loaded: LoadedPolicy,
        result: BuildResult,
        numbers: Numbers,
        findings: MutableList<String>,
    ): String {
        val names = loaded.policy.names
        val blocked = names.learned.filter { (from, to) -> neverMerges(names.never, from, to) }.keys
        val nothing = numbers.all.isEmpty() && numbers.reclaimed.isEmpty() && result.learned.isEmpty()
        if (nothing && blocked.isEmpty()) return loaded.revision
        val what = "${numbers.all.size} assigned numbers, ${numbers.reclaimed.size} reclaimed numbers and " +
            "${result.learned.size} learned aliases"
        fun failed(reason: String, label: String, value: String?): String {
            findings += "policy: $what not written back: $reason"
            log.warn(
                "lineup policy write-back failed {}={} numbers={} learned={}",
                label,
                value,
                numbers.all.size,
                result.learned.size,
            )
            return loaded.revision
        }
        return try {
            val learned = mergeLearned(names.learned, result.learned, blocked)
            file.edit(null) { tree ->
                withNumbers(
                    withLearnedNames(tree, learned, result.learned.keys),
                    numbers.all - numbers.moved.keys,
                    numbers.reclaimed,
                    numbers.moved,
                )
            }.revision
        } catch (e: ApiFailure) {
            failed(e.message, "code", e.code)
        } catch (e: IOException) {
            failed(e.javaClass.simpleName, "class", e.javaClass.simpleName)
        }
    }
}
