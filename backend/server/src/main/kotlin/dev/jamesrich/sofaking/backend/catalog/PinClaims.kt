package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.SourcePin
import dev.jamesrich.sofaking.backend.api.StationOverride
import java.math.BigDecimal

/**
 * What the stations' source pins claim in one run. Pins claim first, by station key then pin order; a channel two
 * pins match goes to the first. A pin that matches no live channel is reported to [findings], and for a required
 * station whose pin a retained ghost still matches, the offerings that pin last resolved to stay while the feed still
 * has them (the sticky snapshot).
 */
internal class PinClaims(
    private val overrides: Map<String, StationOverride>,
    private val live: List<FeedCandidate>,
    private val ghosts: List<FeedCandidate>,
    private val previous: Map<String, StationAvailability>,
    private val findings: MutableList<String>,
) {
    /** Channel ID to the station key and pin index that claims it. */
    val pinnedTo: Map<String, Pair<String, Int>>

    /** Station key to the pin identities kept from the last run and their offering IDs. */
    val sticky: Map<String, List<Pair<String, List<String>>>>

    private val matched = HashSet<Pair<String, Int>>()
    private val liveOfferings = live.flatMapTo(HashSet()) { candidate -> candidate.offerings.map { it.id } }

    init {
        pinnedTo = claim()
        sticky = overrides.mapValues { (key, override) ->
            override.sources.withIndex().mapNotNull { (index, pin) ->
                if (key to index in matched) null else missing(key, override, pin)
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun claim(): Map<String, Pair<String, Int>> {
        // A pin names its provider, so only that provider's channels are matched against it.
        val liveByProvider = live.groupBy { it.providerId }
        val pinnedTo = HashMap<String, Pair<String, Int>>()
        for ((key, override) in overrides) {
            override.sources.forEachIndexed { index, pin ->
                val matches = liveByProvider[pin.provider].orEmpty().filter { pinMatches(pin, it) }
                    .sortedWith(CANDIDATE_ORDER)
                for (candidate in matches) {
                    matched += key to index
                    val owner = pinnedTo.getOrPut(candidate.channel.id) { key to index }
                    if (owner.first != key) {
                        findings += "$key: pin ${pinIdentity(pin)} also matches channel ${candidate.channel.id}, " +
                            "which ${owner.first} pins first"
                    }
                }
            }
        }
        return pinnedTo
    }

    /** Reports [pin] of station [key] as missing; the sticky snapshot it keeps, if any. */
    private fun missing(key: String, override: StationOverride, pin: SourcePin): Pair<String, List<String>>? {
        val identity = pinIdentity(pin)
        val retained = ghosts.filter { pinMatches(pin, it) }.map { it.channel.id }.sorted()
        val kept = if (override.required && retained.isNotEmpty()) {
            previous[key]?.pinOfferings?.get(identity).orEmpty().filter { it in liveOfferings }
        } else {
            emptyList()
        }
        val tail = when {
            kept.isNotEmpty() -> "; keeping ${kept.size} last known sources of retained channel ${retained.first()}"
            retained.isNotEmpty() -> "; retained channel ${retained.first()} has no last known source left"
            else -> ""
        }
        val what = if (override.required) "required" else "pinned"
        findings += "$key: $what source missing: $identity$tail"
        return if (kept.isEmpty()) null else identity to kept
    }

    private companion object {
        val CANDIDATE_ORDER: Comparator<FeedCandidate> =
            compareBy<FeedCandidate, BigDecimal?>(NUMBER_ORDER) { numberValue(it.channel.displayNumber) }
                .thenBy { it.channel.id }
    }
}
