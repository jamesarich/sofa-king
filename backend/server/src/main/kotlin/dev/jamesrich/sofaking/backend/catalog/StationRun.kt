package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.PolicyCandidate
import dev.jamesrich.sofaking.backend.api.PolicyStation
import dev.jamesrich.sofaking.backend.api.StationOverride
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** One station a run builds, before its number and group are settled. */
internal class Draft(
    val key: String,
    val leading: Member?,
    val sources: List<Member>,
    val offeringIds: List<String>,
    val override: StationOverride?,
    val baseEnabled: Boolean,
    val missing: StationAvailability? = null,
    val pinOfferings: Map<String, List<String>> = emptyMap(),
    /** Every eligible member in leading order; each casts the group vote of its raw category. */
    val voters: List<Member> = emptyList(),
) {
    var number: String? = override?.number
}

/** A station's sources up to the policy's cap, and the offerings each of its pins resolved to. */
private class SourceCap(private val cap: Int) {
    val offeringIds = ArrayList<String>()
    val pinOfferings = sortedMapOf<String, List<String>>()
    var dropped = 0
        private set

    /** Takes what of [candidateIds] fits; the room there was before. */
    fun take(candidateIds: List<String>): Int {
        val ids = candidateIds.filter { it !in offeringIds }
        val room = (cap - offeringIds.size).coerceAtLeast(0)
        offeringIds += ids.take(room)
        dropped += (ids.size - room).coerceAtLeast(0)
        return room
    }

    /** Takes each sticky snapshot of [kept] and keeps it as its pin's offerings. */
    fun keep(kept: List<Pair<String, List<String>>>) {
        for ((identity, ids) in kept) {
            take(ids)
            pinOfferings[identity] = ids
        }
    }

    fun pinned(identity: String, ids: List<String>) {
        pinOfferings[identity] = pinOfferings[identity].orEmpty() + ids
    }
}

private val PIN_ORDER = compareBy<Member> { it.pin }.thenBy(NUMBER_ORDER) { it.number }.thenBy { it.id }
private val LEADING_ORDER =
    compareBy<Member> { it.entry ?: Int.MAX_VALUE }.thenBy(NUMBER_ORDER) { it.number }.thenBy { it.id }

/** Unpinned sources: the station's preferred providers in its order, then in leading order. */
private fun preferOrder(prefer: List<String>): Comparator<Member> = compareBy<Member> {
    prefer.indexOf(it.candidate.providerId).let { rank -> if (rank < 0) prefer.size else rank }
}.then(LEADING_ORDER)

/**
 * One [StationBuilder.build]: pins claim channels, the rest join stations by key, each station takes its sources,
 * then availability, missing stations, groups, numbers and the stations themselves.
 */
internal class StationRun(private val input: BuildInput) {
    private val policy = input.policy
    private val keys = stationKeys(policy.names)
    private val overrides = policy.stations.toSortedMap()
    private val entries = policy.providers.map(::CompiledEntry)
    private val findings = ArrayList<String>()
    private val decisions = ArrayList<PolicyCandidate>()
    private val live: List<FeedCandidate>
    private val ghosts: List<FeedCandidate>
    private val claims: PinClaims
    private val members = HashMap<String, MutableList<Member>>()
    private val availability = sortedMapOf<String, StationAvailability>()
    private val autoOff = HashSet<String>()
    private val unverified = HashSet<String>()

    init {
        val all = input.candidates.sortedWith(compareBy({ it.providerId }, { it.channel.id }))
        val (current, gone) = all.partition { it.offerings.isNotEmpty() }
        live = current
        ghosts = gone
        claims = PinClaims(overrides, live, ghosts, input.previous, findings)
    }

    fun build(): BuildResult {
        join()
        val drafts = (members.keys + overrides.filterValues { it.required }.keys).toSortedSet()
            .mapNotNullTo(ArrayList(), ::draft)
        drafts.forEach(::trackAvailability)
        val reclaimed = keepMissing(drafts)
        val groups = StationGroups(policy)
        val numbers = StationNumbering(policy.numbering, groups::groupOf, unverified)
        numbers.number(drafts, overrides.values)
        for (draft in drafts) {
            val record = availability[draft.key] ?: continue
            val name = draft.override?.name ?: draft.leading?.candidate?.channel?.name ?: record.lastName
            availability[draft.key] = record.copy(lastNumber = draft.number, lastName = name)
        }
        val stations = drafts.map { station(it, groups) }
            .sortedWith(
                compareBy<PolicyStation, BigDecimal?>(NUMBER_ORDER) {
                    numberValue(it.number)
                }.thenBy { it.key },
            )
        for ((id, shared) in stations.groupBy { it.id }.toSortedMap()) {
            if (shared.size >
                1
            ) {
                findings += "${shared.first().key}: id $id is shared with ${shared.drop(1).joinToString { it.key }}"
            }
        }
        val (unmapped, unmappedStations) = groups.unmapped(drafts)
        if (unmapped.isNotEmpty()) {
            findings += "groups: ${unmapped.size} categories have no mapping ($unmappedStations stations); " +
                "see unmappedCategories"
        }
        return BuildResult(
            stations, numbers.assigned, findings.sorted(), availability,
            decisions.sortedWith(compareBy({ it.key }, { it.providerId }, { it.channelId })), keys,
            suggestedAliases(drafts.filter { it.sources.isNotEmpty() }.map { it.key }, policy.names.never),
            unmappedCategories = unmapped,
            reclaimed = reclaimed,
            renumbered = numbers.renumbered,
        )
    }

    /** Each live channel joins the station its pin or its name keys; the rest are skipped. */
    private fun join() {
        for (candidate in live) {
            val entry = entries.indexOfFirst { it.admits(candidate) }.takeIf { it >= 0 }
            val pinned = claims.pinnedTo[candidate.channel.id]
            when {
                pinned != null ->
                    members.getOrPut(pinned.first) { mutableListOf() } += Member(candidate, entry, pinned.second)

                entry == null -> decide(candidate, null, "skipped", null, "no provider entry admits it")

                else -> members.getOrPut(keys.key(candidate.channel.name)) { mutableListOf() } +=
                    Member(candidate, entry, null)
            }
        }
        for (ghost in ghosts) decide(ghost, null, "skipped", null, "no current offering")
    }

    private fun decide(candidate: FeedCandidate, entry: Int?, outcome: String, station: String?, reason: String) {
        val channel = candidate.channel
        decisions += PolicyCandidate(
            channel.id, candidate.providerId, channel.displayNumber, channel.name, channel.legacyGuideId,
            keys.key(channel.name), entry, outcome, station, reason,
        )
    }

    /** Station [key] with its sources: pinned ones, then the sticky snapshot, then the rest; null when it has none. */
    private fun draft(key: String): Draft? {
        val override = overrides[key]
        val joined = members[key].orEmpty()
        val excluded = joined.filter { it.pin == null && it.candidate.providerId in override?.exclude.orEmpty() }
        for (member in excluded) {
            val reason = "provider ${member.candidate.providerId} excluded by the station"
            decide(member.candidate, member.entry, "skipped", key, reason)
        }
        val eligible = joined - excluded.toSet()
        if (eligible.isEmpty() && override?.required != true) return null
        val pinned = eligible.filter { it.pin != null }.sortedWith(PIN_ORDER)
        val ordered = pinned + eligible.filter { it.pin == null }.sortedWith(preferOrder(override?.prefer.orEmpty()))
        val voters = eligible.sortedWith(LEADING_ORDER)
        val leading = voters.firstOrNull()
        val sources = SourceCap(policy.maxSourcesPerStation)
        val kept = claims.sticky[key].orEmpty().sortedBy { (identity, _) ->
            override!!.sources.indexOfFirst { pinIdentity(it) == identity }
        }
        if (pinned.isEmpty()) sources.keep(kept)
        for (member in ordered) {
            val ids = member.candidate.offerings.map { it.id }.sorted()
            val room = sources.take(ids)
            if (member === pinned.lastOrNull()) sources.keep(kept)
            val pin = member.pin?.let { pinIdentity(override!!.sources[it]) }
            if (pin != null) sources.pinned(pin, ids)
            decideTaken(key, member, room, pin, leading)
        }
        if (sources.dropped > 0) {
            findings += "$key: ${sources.dropped} sources over the cap of ${policy.maxSourcesPerStation} dropped"
        }
        val baseEnabled = override?.enabled ?: leading?.entry?.let { policy.providers[it].enabled } ?: true
        return Draft(
            key,
            leading,
            ordered,
            sources.offeringIds,
            override,
            baseEnabled,
            pinOfferings = sources.pinOfferings,
            voters = voters,
        )
    }

    private fun decideTaken(key: String, member: Member, room: Int, pin: String?, leading: Member?) {
        val (outcome, reason) = when {
            room <= 0 -> "skipped" to "over the source cap of ${policy.maxSourcesPerStation}"

            pin != null -> (if (keys.key(member.candidate.channel.name) == key) "taken" else "claimed") to
                "pinned by $pin"

            member === leading -> "taken" to "leading candidate"

            else -> "taken" to "fallback source"
        }
        decide(member.candidate, member.entry, outcome, key, reason)
    }

    /** Availability by key: unavailable long enough turns a station off until a source qualifies again. */
    private fun trackAvailability(draft: Draft) {
        val states = draft.offeringIds.mapNotNull { input.health[it] }
        val previous = input.previous[draft.key]
        val since = if (states.any(::eligible)) null else previous?.unavailableSince ?: input.now
        val off = autoDisabled(states, previous?.autoDisabled == true, since)
        if (off) autoOff += draft.key
        val qualified = states.any { it.status == HealthStatus.HEALTHY || it.lastQualifiedAt != null } ||
            draft.offeringIds.any { it in input.seedVerified }
        val verifiedAt = previous?.verifiedAt ?: if (qualified) input.now else null
        if (policy.health.requireVerified && verifiedAt == null) unverified += draft.key
        availability[draft.key] =
            StationAvailability(since, off, pinOfferings = draft.pinOfferings, verifiedAt = verifiedAt)
        if (draft.offeringIds.isEmpty()) findings += "${draft.key}: required station has no live source"
    }

    /**
     * Whether a station with sources in [states] stays off: one the last run turned off until a source recovers, else
     * one unavailable [since] for longer than disableAfterHours.
     */
    private fun autoDisabled(states: List<HealthState>, wasOff: Boolean, since: Instant?): Boolean {
        val health = policy.health
        if (wasOff) {
            val healthy = states.any { !it.manuallyDisabled && it.status == HealthStatus.HEALTHY }
            return !(healthy || (health.enableOn == "eligible" && states.any(::eligible)))
        }
        return since != null &&
            Duration.between(since, input.now) >= Duration.ofHours(health.disableAfterHours.toLong())
    }

    /**
     * Stations whose candidates are gone stay, disabled, for keepMissingDays; required ones never leave. A key an
     * alias now joins into another station merged rather than vanished. Adds the ones kept to [drafts]; the keys
     * that left.
     */
    private fun keepMissing(drafts: MutableList<Draft>): Set<String> {
        val present = drafts.mapTo(HashSet()) { it.key }
        val keepFor = Duration.ofDays(policy.health.keepMissingDays.toLong())
        val reclaimed = HashSet<String>()
        for ((key, previous) in input.previous.toSortedMap()) {
            if (key in present || keys.resolve(key) != key) continue
            val missingSince = previous.missingSince ?: input.now
            if (overrides[key]?.required != true && Duration.between(missingSince, input.now) >= keepFor) {
                reclaimed += key
            } else {
                availability[key] = previous.copy(missingSince = missingSince)
                drafts += Draft(key, null, emptyList(), emptyList(), overrides[key], false, previous)
                findings += "$key: candidates missing since $missingSince"
            }
        }
        return reclaimed
    }

    private fun station(draft: Draft, groups: StationGroups): PolicyStation {
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
        // The first guide that has programs ahead, else the first that lists the station at all.
        val guides = if (override?.guide != null) emptyList() else guidesFor(draft.sources, input)
        return PolicyStation(
            id = override?.id?.lowercase() ?: stationId(draft.key),
            key = draft.key,
            number = draft.number!!,
            name = override?.name ?: leading?.name ?: draft.missing?.lastName
                ?: override?.sources?.firstNotNullOfOrNull { it.name } ?: draft.key,
            group = groups.groupOf(draft),
            logo = override?.logo ?: leading?.logo,
            guide = override?.guide ?: guides.firstOrNull(input.guideCovered) ?: guides.firstOrNull(),
            enabled = reason == null,
            required = override?.required == true,
            offeringIds = draft.offeringIds,
            disabledReason = reason,
            guideCandidates = guides,
            gracenoteId = override?.gracenoteId,
        )
    }
}

/**
 * For each source, in station order, whose `tvg-id` a guide lists, that guide: its provider's own guide first, then
 * a guide that is not another provider's. Provider guides key channels by that provider's ids.
 */
private fun guidesFor(sources: List<Member>, input: BuildInput): List<GuideMapping> = sources.mapNotNull { member ->
    val tvg = member.candidate.channel.legacyGuideId ?: return@mapNotNull null
    val listing = input.guidesListing(tvg)
    val guideId = listing.firstOrNull { it == member.candidate.providerId }
        ?: listing.firstOrNull { it !in input.providerIds }
        ?: return@mapNotNull null
    GuideMapping(guideId, tvg)
}.distinct()
