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
    /** Keys that left the lineup this run after keepMissingDays; a write-back reclaims their server-assigned numbers. */
    val reclaimed: Set<String> = emptySet(),
) {
    /** This result with [learning] applied: its aliases recorded, its suggestions and findings added. */
    fun with(learning: Learning): BuildResult = BuildResult(
        stations, assigned, (findings + learning.findings).sorted(), availability, candidates, keys,
        (suggestedAliases + learning.suggestions).distinct().sorted(), learning.learned, unmappedCategories, reclaimed,
    )
}

private val NUMBER_ORDER: Comparator<BigDecimal?> = nullsLast(naturalOrder())

private fun numberValue(number: String?): BigDecimal? = number?.toBigDecimalOrNull()

private fun nextFree(used: Set<String>, floor: Int): String {
    var candidate = floor
    while (candidate.toString() in used) candidate++
    return candidate.toString()
}

/** The first number in [from, until) not in [used]; null when the block is full. */
private fun nextFreeIn(used: Set<String>, from: Int, until: Int): String? = (from until until).firstOrNull { it.toString() !in used }?.toString()

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
        val keys = stationKeys(policy.names)
        val liveOfferings = live.flatMapTo(HashSet()) { candidate -> candidate.offerings.map { it.id } }
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
            val baseEnabled: Boolean, val missing: StationAvailability? = null, val pinOfferings: Map<String, List<String>> = emptyMap(),
            /** Every eligible member in leading order; each casts the group vote of its raw category. */
            val voters: List<Member> = emptyList(),
        ) {
            var number: String? = override?.number
        }

        // Pins that matched no live channel. For a required station whose pin a retained ghost still matches, the
        // offerings that pin last resolved to stay while the feed still has them (the sticky snapshot).
        val sticky = HashMap<String, MutableList<Pair<String, List<String>>>>()
        for ((key, override) in overrides) {
            override.sources.forEachIndexed { index, pin ->
                if (key to index in matchedPins) return@forEachIndexed
                val identity = pinIdentity(pin)
                val retained = ghosts.filter { pinMatches(pin, it) }.map { it.channel.id }.sorted()
                val kept = if (override.required && retained.isNotEmpty()) input.previous[key]?.pinOfferings?.get(identity).orEmpty().filter { it in liveOfferings } else emptyList()
                if (kept.isNotEmpty()) sticky.getOrPut(key) { mutableListOf() } += identity to kept
                val tail = when {
                    kept.isNotEmpty() -> "; keeping ${kept.size} last known sources of retained channel ${retained.first()}"
                    retained.isNotEmpty() -> "; retained channel ${retained.first()} has no last known source left"
                    else -> ""
                }
                findings += if (override.required) "$key: required source missing: $identity$tail" else "$key: pinned source missing: $identity$tail"
            }
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
            val voters = eligible.sortedWith(compareBy<Member> { it.entry ?: Int.MAX_VALUE }.thenBy(NUMBER_ORDER) { it.number }.thenBy { it.id })
            val leading = voters.firstOrNull()
            val offeringIds = ArrayList<String>()
            val pinOfferings = sortedMapOf<String, List<String>>()
            var dropped = 0
            fun take(candidateIds: List<String>): Int {
                val ids = candidateIds.filter { it !in offeringIds }
                val room = (policy.maxSourcesPerStation - offeringIds.size).coerceAtLeast(0)
                offeringIds += ids.take(room)
                dropped += (ids.size - room).coerceAtLeast(0)
                return room
            }
            fun takeSticky() {
                val kept = sticky[key].orEmpty().sortedBy { (identity, _) -> override!!.sources.indexOfFirst { pinIdentity(it) == identity } }
                for ((identity, ids) in kept) { take(ids); pinOfferings[identity] = ids }
            }
            // Pinned sources, then the sticky snapshot, then the rest.
            if (pinned.isEmpty()) takeSticky()
            for (member in ordered) {
                val room = take(member.candidate.offerings.map { it.id }.sorted())
                if (member === pinned.lastOrNull()) takeSticky()
                if (member.pin != null) {
                    val identity = pinIdentity(override!!.sources[member.pin])
                    pinOfferings[identity] = pinOfferings[identity].orEmpty() + member.candidate.offerings.map { it.id }.sorted()
                }
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
            drafts += Draft(key, leading, ordered, offeringIds, override, baseEnabled, pinOfferings = pinOfferings, voters = voters)
        }

        // Availability by key: unavailable long enough turns a station off until a source qualifies again.
        val availability = sortedMapOf<String, StationAvailability>()
        val autoOff = HashSet<String>()
        val unverified = HashSet<String>()
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
            val qualified = states.any { it.status == HealthStatus.HEALTHY || it.lastQualifiedAt != null } ||
                draft.offeringIds.any { it in input.seedVerified }
            val verifiedAt = previous?.verifiedAt ?: if (qualified) input.now else null
            if (policy.health.requireVerified && verifiedAt == null) unverified += draft.key
            availability[draft.key] = StationAvailability(since, off, pinOfferings = draft.pinOfferings, verifiedAt = verifiedAt)
            if (draft.offeringIds.isEmpty()) findings += "${draft.key}: required station has no live source"
        }
        // Stations whose candidates are gone stay, disabled, for keepMissingDays; required ones never leave. A key an
        // alias now joins into another station merged rather than vanished.
        val present = drafts.mapTo(HashSet()) { it.key }
        val reclaimed = HashSet<String>()
        for ((key, previous) in input.previous.toSortedMap()) {
            if (key in present || keys.resolve(key) != key) continue
            val missingSince = previous.missingSince ?: input.now
            val required = overrides[key]?.required == true
            if (!required && Duration.between(missingSince, input.now) >= Duration.ofDays(policy.health.keepMissingDays.toLong())) {
                reclaimed += key
                continue
            }
            availability[key] = previous.copy(missingSince = missingSince)
            drafts += Draft(key, null, emptyList(), emptyList(), overrides[key], false, previous)
            findings += "$key: candidates missing since $missingSince"
        }

        // A station's group: its override, else a name pattern, else its sources' vote, else the default, else the
        // leading channel's raw group.
        val weak = policy.groups.weak.mapTo(HashSet()) { it.trim().lowercase() }
        val namePatterns = policy.groups.patterns.mapNotNull { p -> p.name?.let { Regex(it, RegexOption.IGNORE_CASE) to p.group } }
        val rawPatterns = policy.groups.patterns.mapNotNull { p -> p.rawGroup?.let { Regex(it, RegexOption.IGNORE_CASE) to p.group } }
        fun rawOf(member: Member): String? = member.candidate.channel.tags.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        fun isWeak(member: Member, raw: String): Boolean = raw.lowercase() in weak || "${member.candidate.providerId}:${raw.lowercase()}" in weak
        fun mapped(member: Member, raw: String): String? = policy.groups.map["${member.candidate.providerId}:${raw.lowercase()}"]
            ?: policy.groups.map[raw.lowercase()] ?: rawPatterns.firstOrNull { it.first.containsMatchIn(raw) }?.second
        fun nameGroup(draft: Draft): String? = (draft.override?.name ?: draft.leading?.candidate?.channel?.name)
            ?.let { name -> namePatterns.firstOrNull { it.first.containsMatchIn(name) }?.second }
        // Each provider counts once per group; a tie goes to the group whose first vote comes earliest in leading order.
        fun elect(votes: List<Pair<String, String>>): String? {
            val tally = votes.distinct().groupingBy { it.second }.eachCount()
            val best = tally.values.maxOrNull() ?: return null
            return votes.first { tally[it.second] == best }.second
        }
        fun votedGroup(draft: Draft): String? {
            val cast = draft.voters.mapNotNull { member ->
                val raw = rawOf(member) ?: return@mapNotNull null
                mapped(member, raw)?.let { Triple(member.candidate.providerId, it, isWeak(member, raw)) }
            }
            return elect(cast.filter { !it.third }.map { it.first to it.second }) ?: elect(cast.map { it.first to it.second })
        }
        val groupByKey = HashMap<String, String?>()
        fun groupFor(draft: Draft): String? = groupByKey.getOrPut(draft.key) {
            draft.override?.group ?: nameGroup(draft) ?: votedGroup(draft) ?: policy.groups.default ?: draft.leading?.let(::rawOf)
        }

        // Pass one reserves every number the file holds, including a station that has just left, and the numbers missing
        // stations still hold; pass two assigns the rest, in the station's group block when numbering.groups names its group.
        val used = HashSet<String>()
        for (override in overrides.values) override.number?.let { used += canonicalNumber(it) }
        for (draft in drafts.filter { it.number == null && it.missing?.lastNumber != null }.sortedBy { it.key }) {
            val last = canonicalNumber(draft.missing!!.lastNumber!!)
            if (used.add(last)) draft.number = last
        }
        val assigned = sortedMapOf<String, String>()
        val pending = drafts.filter { it.number == null }.sortedWith(
            compareBy<Draft> { it.leading?.entry ?: Int.MAX_VALUE }.thenBy(NUMBER_ORDER) { it.leading?.number }.thenBy { it.key },
        )
        val numbering = policy.numbering
        val starts = numbering.groups.values.sorted()
        // A block ends at its size or where the next group's block starts, whichever is first.
        fun blockEnd(start: Int): Int = minOf(start + numbering.groupSize, starts.firstOrNull { it > start } ?: Int.MAX_VALUE)
        for (draft in pending) {
            val provider = draft.leading?.candidate?.channel?.displayNumber?.takeIf { it.matches(STATION_NUMBER) }?.let(::canonicalNumber)
            // An unverified station holds a floor number that is never written, so its block keeps for a verified station.
            val temporary = draft.baseEnabled && draft.key in unverified
            // Stations the policy keeps off number from the floor, so group blocks hold only what viewers can see.
            val block = if (draft.baseEnabled) numbering.groups[groupFor(draft)] else null
            val number = when {
                temporary -> nextFree(used, numbering.floor)
                block != null -> nextFreeIn(used, block, blockEnd(block)) ?: nextFree(used, numbering.floor)
                numbering.groups.isEmpty() && numbering.keepProviderNumbers && provider != null && provider !in used -> provider
                else -> nextFree(used, numbering.floor)
            }
            used += number
            draft.number = number
            if (!temporary) assigned[draft.key] = number
        }
        for (draft in drafts) {
            val record = availability[draft.key] ?: continue
            availability[draft.key] = record.copy(lastNumber = draft.number, lastName = draft.override?.name ?: draft.leading?.candidate?.channel?.name ?: record.lastName)
        }

        val stations = drafts.map { draft ->
            val override = draft.override
            val leading = draft.leading?.candidate?.channel
            val reason = when {
                draft.missing != null -> "missing"
                !draft.baseEnabled -> "policy"
                draft.offeringIds.isEmpty() -> "no-source"
                draft.key in unverified -> "unverified"
                draft.key in autoOff -> "unavailable"
                else -> null
            }
            PolicyStation(
                id = override?.id?.lowercase() ?: stationId(draft.key),
                key = draft.key,
                number = draft.number!!,
                name = override?.name ?: leading?.name ?: draft.missing?.lastName ?: override?.sources?.firstNotNullOfOrNull { it.name } ?: draft.key,
                group = groupFor(draft),
                logo = override?.logo ?: leading?.logo,
                guide = override?.guide ?: guideFor(draft.sources, input),
                enabled = reason == null,
                required = override?.required == true,
                offeringIds = draft.offeringIds,
                disabledReason = reason,
            )
        }.sortedWith(compareBy<PolicyStation, BigDecimal?>(NUMBER_ORDER) { numberValue(it.number) }.thenBy { it.key })

        for ((id, group) in stations.groupBy { it.id }.toSortedMap()) if (group.size > 1) findings += "${group.first().key}: id $id is shared with ${group.drop(1).joinToString { it.key }}"
        // Raw categories of any source that nothing maps and that are not weak, by provider and lowercase category, for
        // the stations whose group the vote decides.
        data class Unmapped(val provider: String, val category: String, val key: String, val name: String)
        val unmappedRows = drafts.flatMap { draft ->
            val leading = draft.leading ?: return@flatMap emptyList()
            if (draft.override?.group != null || nameGroup(draft) != null) return@flatMap emptyList()
            val name = draft.override?.name ?: leading.candidate.channel.name
            draft.voters.mapNotNull { member ->
                val raw = rawOf(member) ?: return@mapNotNull null
                if (mapped(member, raw) != null || isWeak(member, raw)) null else Unmapped(member.candidate.providerId, raw.lowercase(), draft.key, name)
            }.distinct()
        }
        val unmappedCategories = unmappedRows.groupBy { it.provider to it.category }.map { (category, rows) ->
            PolicyUnmappedCategory(category.first, category.second, rows.size, rows.map { it.name }.sorted().first())
        }.sortedWith(compareByDescending<PolicyUnmappedCategory> { it.stations }.thenBy { it.provider }.thenBy { it.category })
        if (unmappedCategories.isNotEmpty()) {
            findings += "groups: ${unmappedCategories.size} categories have no mapping (${unmappedRows.distinctBy { it.key }.size} stations); see unmappedCategories"
        }

        return BuildResult(
            stations, assigned, findings.sorted(), availability,
            decisions.sortedWith(compareBy({ it.key }, { it.providerId }, { it.channelId })), keys,
            suggestedAliases(drafts.filter { it.sources.isNotEmpty() }.map { it.key }, policy.names.never),
            unmappedCategories = unmappedCategories,
            reclaimed = reclaimed,
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
