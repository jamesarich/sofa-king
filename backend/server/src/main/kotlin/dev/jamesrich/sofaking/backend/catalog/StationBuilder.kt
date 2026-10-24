package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.CandidateFilter
import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.PolicyCandidate
import dev.jamesrich.sofaking.backend.api.PolicyProvider
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.PolicyUnmappedCategory
import dev.jamesrich.sofaking.backend.api.SourcePin
import dev.jamesrich.sofaking.backend.api.StationOverride
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.setup.DefaultGroups
import dev.jamesrich.sofaking.contract.Channel
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** One feed channel a run considers, with its provider and current offerings; none makes it a ghost. */
data class FeedCandidate(val channel: Channel, val providerId: String, val offerings: List<Offering>)

/**
 * What a run remembers per station key for the next: since when it has had no eligible source, whether that
 * turned it off, since when its candidates are gone, and the number and name it last had.
 */
data class StationAvailability(
    val unavailableSince: Instant? = null,
    val autoDisabled: Boolean = false,
    val missingSince: Instant? = null,
    val lastNumber: String? = null,
    val lastName: String? = null,
    /** Pin identity to the offering IDs it last resolved to; what a required station's sticky snapshot keeps. */
    val pinOfferings: Map<String, List<String>> = emptyMap(),
    /** When a run first saw one of the station's sources qualified healthy; null while it never has. */
    val verifiedAt: Instant? = null,
)

/** Everything a run reads. [guidesListing] gives the guides that list a `tvg-id`, in configuration order. */
class BuildInput(
    val policy: LineupPolicy,
    val candidates: List<FeedCandidate>,
    val guidesListing: (String) -> List<String>,
    /** Configured provider IDs; a provider's own guide carries its ID. */
    val providerIds: Set<String>,
    val health: Map<String, HealthState>,
    val previous: Map<String, StationAvailability>,
    val now: Instant,
    /** Offerings that count as verified without a probe: the enabled lineup a first policy run replaces. */
    val seedVerified: Set<String> = emptySet(),
    /** Whether a guide holds programs ahead for a channel; a station prefers a covered guide over an empty one. */
    val guideCovered: (GuideMapping) -> Boolean = { true },
)

/**
 * A run's output. [stations] are in lineup order; [assigned] holds the numbers pass two gave (key to number);
 * [findings] each start with the station key; [candidates] says what happened to every feed channel.
 */
class BuildResult(
    val stations: List<PolicyStation>,
    val assigned: Map<String, String>,
    val findings: List<String>,
    val availability: Map<String, StationAvailability>,
    val candidates: List<PolicyCandidate>,
    val keys: StationKeys,
    /** Pairs of live station keys that probably name one station, as `<key>: possible alias of <other key>`. */
    val suggestedAliases: List<String> = emptyList(),
    /** Aliases this run learned from matching schedules and built with, not yet in the policy file. */
    val learned: Map<String, String> = emptyMap(),
    /** Raw categories the group map and patterns do not name, most stations first. */
    val unmappedCategories: List<PolicyUnmappedCategory> = emptyList(),
    /**
     * Keys that left the lineup this run after keepMissingDays; a write-back reclaims their server-assigned numbers.
     */
    val reclaimed: Set<String> = emptySet(),
    /** Server-numbered stations that followGroup moved into their group's block, key to new number. */
    val renumbered: Map<String, String> = emptyMap(),
) {
    /** This result with [learning] applied: its aliases recorded, its suggestions and findings added. */
    fun with(learning: Learning): BuildResult = BuildResult(
        stations, assigned, (findings + learning.findings).sorted(), availability, candidates, keys,
        (suggestedAliases + learning.suggestions).distinct().sorted(), learning.learned, unmappedCategories, reclaimed,
        renumbered,
    )
}

internal val NUMBER_ORDER: Comparator<BigDecimal?> = nullsLast(naturalOrder())

internal fun numberValue(number: String?): BigDecimal? = number?.toBigDecimalOrNull()

internal fun nextFree(used: Set<String>, floor: Int): String {
    var candidate = floor
    while (candidate.toString() in used) candidate++
    return candidate.toString()
}

/** The first number in [from, until) not in [used]; null when the block is full. */
internal fun nextFreeIn(used: Set<String>, from: Int, until: Int): String? = (from until until).firstOrNull {
    it.toString() !in
        used
}?.toString()

private class CompiledFilter(filter: CandidateFilter) {
    private val groups = filter.groups.mapTo(HashSet()) { it.trim().lowercase() }
    private val names = filter.names.mapTo(HashSet()) { it.trim().lowercase() }
    private val groupPatterns = filter.groupPatterns.map { Regex(it, RegexOption.IGNORE_CASE) }
    private val namePatterns = filter.namePatterns.map { Regex(it, RegexOption.IGNORE_CASE) }

    fun matches(channel: Channel): Boolean {
        val group = channel.tags.firstOrNull()
        return (
            group != null && (
                group.trim().lowercase() in groups || groupPatterns.any {
                    it.containsMatchIn(group)
                }
                )
            ) ||
            channel.name.trim().lowercase() in names || namePatterns.any { it.containsMatchIn(channel.name) }
    }
}

internal class CompiledEntry(val entry: PolicyProvider) {
    private val include = entry.include?.let(::CompiledFilter)
    private val exclude = entry.exclude?.let(::CompiledFilter)

    fun admits(candidate: FeedCandidate): Boolean =
        candidate.providerId == entry.id && (include?.matches(candidate.channel) ?: true) &&
            exclude?.matches(candidate.channel) != true
}

internal class Member(val candidate: FeedCandidate, val entry: Int?, val pin: Int?) {
    val id: String get() = candidate.channel.id
    val number: BigDecimal? = numberValue(candidate.channel.displayNumber)
}

internal fun pinMatches(pin: SourcePin, candidate: FeedCandidate): Boolean {
    if (pin.provider != candidate.providerId) return false
    val tvg = candidate.channel.legacyGuideId
    if (pin.tvgId != null && tvg == pin.tvgId) return true
    val name = pin.name ?: return false
    return (pin.tvgId == null || tvg == null) && candidate.channel.name.trim().equals(name.trim(), ignoreCase = true)
}

/**
 * Builds the lineup a policy describes from one snapshot of feeds, guides and health. Pure and deterministic:
 * every ordering is a total order on the inputs' values, so input order and map iteration never show through.
 */
object StationBuilder {
    fun build(input: BuildInput): BuildResult = StationRun(input).build()
}
