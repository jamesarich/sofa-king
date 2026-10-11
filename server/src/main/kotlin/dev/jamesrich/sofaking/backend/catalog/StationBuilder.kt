package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
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
    val unavailableSince: Instant? = null, val autoDisabled: Boolean = false, val missingSince: Instant? = null,
    val lastNumber: String? = null, val lastName: String? = null,
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
)

private val NUMBER_ORDER: Comparator<BigDecimal?> = nullsLast(naturalOrder())

private fun numberValue(number: String?): BigDecimal? = number?.toBigDecimalOrNull()

private fun nextFree(used: Set<String>, floor: Int): String {
    var candidate = floor
    while (candidate.toString() in used) candidate++
    return candidate.toString()
}

private class CompiledFilter(filter: CandidateFilter) {
    private val groups = filter.groups.mapTo(HashSet()) { it.trim().lowercase() }
    private val names = filter.names.mapTo(HashSet()) { it.trim().lowercase() }
    private val groupPatterns = filter.groupPatterns.map { Regex(it, RegexOption.IGNORE_CASE) }
    private val namePatterns = filter.namePatterns.map { Regex(it, RegexOption.IGNORE_CASE) }

    fun matches(channel: Channel): Boolean {
        val group = channel.tags.firstOrNull()
        return (group != null && (group.trim().lowercase() in groups || groupPatterns.any { it.containsMatchIn(group) })) ||
            channel.name.trim().lowercase() in names || namePatterns.any { it.containsMatchIn(channel.name) }
    }
}

private class CompiledEntry(val entry: PolicyProvider) {
    private val include = entry.include?.let(::CompiledFilter)
    private val exclude = entry.exclude?.let(::CompiledFilter)

    fun admits(candidate: FeedCandidate): Boolean =
        candidate.providerId == entry.id && (include?.matches(candidate.channel) ?: true) && exclude?.matches(candidate.channel) != true
}

private class Member(val candidate: FeedCandidate, val entry: Int?, val pin: Int?) {
    val id: String get() = candidate.channel.id
    val number: BigDecimal? = numberValue(candidate.channel.displayNumber)
}

private fun pinMatches(pin: SourcePin, candidate: FeedCandidate): Boolean {
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
    fun build(input: BuildInput): BuildResult {
        val policy = input.policy
        val findings = ArrayList<String>()
        val decisions = ArrayList<PolicyCandidate>()
        val all = input.candidates.sortedWith(compareBy({ it.providerId }, { it.channel.id }))
        val (live, ghosts) = all.partition { it.offerings.isNotEmpty() }
        val keys = StationKeys(live.map { it.channel.name }, policy.names.aliases, policy.names.version)
        val entries = policy.providers.map(::CompiledEntry)
        val overrides = policy.stations.toSortedMap()

        // Pins claim first, by station key then pin order; a channel two pins match goes to the first.
        val pinnedTo = HashMap<String, Pair<String, Int>>()
        val matchedPins = HashSet<Pair<String, Int>>()
        for ((key, override) in overrides) {
            override.sources.forEachIndexed { index, pin ->
                for (candidate in live.filter { pinMatches(pin, it) }.sortedWith(compareBy<FeedCandidate, BigDecimal?>(NUMBER_ORDER) { numberValue(it.channel.displayNumber) }.thenBy { it.channel.id })) {
                    matchedPins += key to index
                    val owner = pinnedTo[candidate.channel.id]
                    if (owner == null) pinnedTo[candidate.channel.id] = key to index
                    else if (owner.first != key) findings += "$key: pin ${pinIdentity(pin)} also matches channel ${candidate.channel.id}, which ${owner.first} pins first"
                }
            }
        }

        val members = HashMap<String, MutableList<Member>>()
        fun decide(candidate: FeedCandidate, entry: Int?, outcome: String, station: String?, reason: String) {
            val channel = candidate.channel
            decisions += PolicyCandidate(channel.id, candidate.providerId, channel.displayNumber, channel.name, channel.legacyGuideId, keys.key(channel.name), entry, outcome, station, reason)
        }
        for (candidate in live) {
            val entry = entries.indexOfFirst { it.admits(candidate) }.takeIf { it >= 0 }
            val pinned = pinnedTo[candidate.channel.id]
            when {
                pinned != null -> members.getOrPut(pinned.first) { mutableListOf() } += Member(candidate, entry, pinned.second)
                entry == null -> decide(candidate, null, "skipped", null, "no provider entry admits it")
                else -> members.getOrPut(keys.key(candidate.channel.name)) { mutableListOf() } += Member(candidate, entry, null)
            }
        }
        for (ghost in ghosts) decide(ghost, null, "skipped", null, "no current offering")

        class Draft(
            val key: String, val leading: Member?, val sources: List<Member>, val offeringIds: List<String>, val override: StationOverride?,
            val baseEnabled: Boolean, val missing: StationAvailability? = null,
        ) {
            var number: String? = override?.number
        }

        val drafts = ArrayList<Draft>()
        val stationKeys = (members.keys + overrides.filterValues { it.required }.keys).toSortedSet()
        for (key in stationKeys) {
            val override = overrides[key]
            val joined = members[key].orEmpty()
            val excluded = joined.filter { it.pin == null && it.candidate.providerId in override?.exclude.orEmpty() }
            for (member in excluded) decide(member.candidate, member.entry, "skipped", key, "provider ${member.candidate.providerId} excluded by the station")
            val eligible = joined - excluded.toSet()
            if (eligible.isEmpty() && override?.required != true) continue
            val prefer = override?.prefer.orEmpty()
            val pinned = eligible.filter { it.pin != null }.sortedWith(compareBy<Member> { it.pin }.thenBy(NUMBER_ORDER) { it.number }.thenBy { it.id })
            val rest = eligible.filter { it.pin == null }.sortedWith(
                compareBy<Member> { prefer.indexOf(it.candidate.providerId).let { rank -> if (rank < 0) prefer.size else rank } }
                    .thenBy { it.entry ?: Int.MAX_VALUE }.thenBy(NUMBER_ORDER) { it.number }.thenBy { it.id },
            )
            val ordered = pinned + rest
            val leading = eligible.minWithOrNull(compareBy<Member> { it.entry ?: Int.MAX_VALUE }.thenBy(NUMBER_ORDER) { it.number }.thenBy { it.id })
            val offeringIds = ArrayList<String>()
            var dropped = 0
            for (member in ordered) {
                val ids = member.candidate.offerings.map { it.id }.sorted().filter { it !in offeringIds }
                val room = policy.maxSourcesPerStation - offeringIds.size
                offeringIds += ids.take(room.coerceAtLeast(0))
                dropped += (ids.size - room.coerceAtLeast(0)).coerceAtLeast(0)
                val nameKey = keys.key(member.candidate.channel.name)
                when {
                    room <= 0 -> decide(member.candidate, member.entry, "skipped", key, "over the source cap of ${policy.maxSourcesPerStation}")
                    member.pin != null -> decide(member.candidate, member.entry, if (nameKey == key) "taken" else "claimed", key, "pinned by ${pinIdentity(override!!.sources[member.pin])}")
                    member === leading -> decide(member.candidate, member.entry, "taken", key, "leading candidate")
                    else -> decide(member.candidate, member.entry, "taken", key, "fallback source")
                }
            }
            if (dropped > 0) findings += "$key: $dropped sources over the cap of ${policy.maxSourcesPerStation} dropped"
            val baseEnabled = override?.enabled ?: leading?.entry?.let { policy.providers[it].enabled } ?: true
            drafts += Draft(key, leading, ordered, offeringIds, override, baseEnabled)
        }

        // Pins that matched no live channel; a ghost that would match is what a sticky snapshot keeps.
        for ((key, override) in overrides) {
            override.sources.forEachIndexed { index, pin ->
                if (key to index in matchedPins) return@forEachIndexed
                val retained = ghosts.filter { pinMatches(pin, it) }.map { it.channel.id }.sorted()
                val tail = if (retained.isEmpty()) "" else "; retained channel ${retained.first()} could back it"
                findings += if (override.required) "$key: required source missing: ${pinIdentity(pin)}$tail" else "$key: pinned source missing: ${pinIdentity(pin)}$tail"
            }
        }

        // Availability by key: unavailable long enough turns a station off until a source qualifies again.
        val availability = sortedMapOf<String, StationAvailability>()
        val autoOff = HashSet<String>()
        val disableAfter = Duration.ofHours(policy.health.disableAfterHours.toLong())
        for (draft in drafts) {
            val states = draft.offeringIds.mapNotNull { input.health[it] }
            val eligibleNow = states.any(::eligible)
            val healthyNow = states.any { !it.manuallyDisabled && it.status == HealthStatus.HEALTHY }
            val previous = input.previous[draft.key]
            val since = if (eligibleNow) null else previous?.unavailableSince ?: input.now
            var off = previous?.autoDisabled == true
            if (!off && since != null && Duration.between(since, input.now) >= disableAfter) off = true
            else if (off && (healthyNow || (policy.health.enableOn == "eligible" && eligibleNow))) off = false
            if (off) autoOff += draft.key
            availability[draft.key] = StationAvailability(since, off)
            if (draft.offeringIds.isEmpty()) findings += "${draft.key}: required station has no live source"
        }
        // Stations whose candidates are gone stay, disabled, for keepMissingDays; required ones never leave.
        val present = drafts.mapTo(HashSet()) { it.key }
        for ((key, previous) in input.previous.toSortedMap()) {
            if (key in present) continue
            val missingSince = previous.missingSince ?: input.now
            val required = overrides[key]?.required == true
            if (!required && Duration.between(missingSince, input.now) >= Duration.ofDays(policy.health.keepMissingDays.toLong())) continue
            availability[key] = previous.copy(missingSince = missingSince)
            drafts += Draft(key, null, emptyList(), emptyList(), overrides[key], false, previous)
            findings += "$key: candidates missing since $missingSince"
        }

        // Pass one reserves override numbers and the numbers missing stations still hold; pass two assigns the rest.
        val used = HashSet<String>()
        for (draft in drafts) draft.override?.number?.let { used += canonicalNumber(it) }
        for (draft in drafts.filter { it.number == null && it.missing?.lastNumber != null }.sortedBy { it.key }) {
            val last = canonicalNumber(draft.missing!!.lastNumber!!)
            if (used.add(last)) draft.number = last
        }
        val assigned = sortedMapOf<String, String>()
        val pending = drafts.filter { it.number == null }.sortedWith(
            compareBy<Draft> { it.leading?.entry ?: Int.MAX_VALUE }.thenBy(NUMBER_ORDER) { it.leading?.number }.thenBy { it.key },
        )
        for (draft in pending) {
            val provider = draft.leading?.candidate?.channel?.displayNumber?.takeIf { it.matches(STATION_NUMBER) }?.let(::canonicalNumber)
            val number = if (policy.numbering.keepProviderNumbers && provider != null && provider !in used) provider else nextFree(used, policy.numbering.floor)
            used += number
            draft.number = number
            assigned[draft.key] = number
        }
        for (draft in drafts) {
            val record = availability[draft.key] ?: continue
            availability[draft.key] = record.copy(lastNumber = draft.number, lastName = draft.override?.name ?: draft.leading?.candidate?.channel?.name ?: record.lastName)
        }

        val groupPatterns = policy.groups.patterns.map { Triple(it.name?.let { p -> Regex(p, RegexOption.IGNORE_CASE) }, it.rawGroup?.let { p -> Regex(p, RegexOption.IGNORE_CASE) }, it.group) }
        fun groupOf(name: String?, raw: String?): String? =
            raw?.let { policy.groups.map[it.trim().lowercase()] }
                ?: groupPatterns.firstOrNull { (byName, byGroup, _) -> (byName != null && name != null && byName.containsMatchIn(name)) || (byGroup != null && raw != null && byGroup.containsMatchIn(raw)) }?.third
                ?: policy.groups.default ?: raw

        val stations = drafts.map { draft ->
            val override = draft.override
            val leading = draft.leading?.candidate?.channel
            val reason = when {
                draft.missing != null -> "missing"
                !draft.baseEnabled -> "policy"
                draft.offeringIds.isEmpty() -> "no-source"
                draft.key in autoOff -> "unavailable"
                else -> null
            }
            PolicyStation(
                id = override?.id?.lowercase() ?: stationId(draft.key),
                key = draft.key,
                number = draft.number!!,
                name = override?.name ?: leading?.name ?: draft.missing?.lastName ?: override?.sources?.firstNotNullOfOrNull { it.name } ?: draft.key,
                group = override?.group ?: groupOf(leading?.name, leading?.tags?.firstOrNull()),
                logo = override?.logo ?: leading?.logo,
                guide = override?.guide ?: guideFor(draft.sources, input),
                enabled = reason == null,
                required = override?.required == true,
                offeringIds = draft.offeringIds,
                disabledReason = reason,
            )
        }.sortedWith(compareBy<PolicyStation, BigDecimal?>(NUMBER_ORDER) { numberValue(it.number) }.thenBy { it.key })

        for ((id, group) in stations.groupBy { it.id }.toSortedMap()) if (group.size > 1) findings += "${group.first().key}: id $id is shared with ${group.drop(1).joinToString { it.key }}"
        return BuildResult(
            stations, assigned, findings.sorted(), availability,
            decisions.sortedWith(compareBy({ it.key }, { it.providerId }, { it.channelId })), keys,
        )
    }

    /**
     * The first source, in station order, whose `tvg-id` a guide lists: its provider's own guide first, then a guide
     * that is not another provider's. Provider guides key channels by that provider's ids.
     */
    private fun guideFor(sources: List<Member>, input: BuildInput): GuideMapping? {
        for (member in sources) {
            val tvg = member.candidate.channel.legacyGuideId ?: continue
            val listing = input.guidesListing(tvg)
            val guideId = listing.firstOrNull { it == member.candidate.providerId } ?: listing.firstOrNull { it !in input.providerIds } ?: continue
            return GuideMapping(guideId, tvg)
        }
        return null
    }
}
