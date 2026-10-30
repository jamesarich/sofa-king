package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedCandidate
import dev.jamesrich.sofaking.backend.catalog.IptvOrgEnriched
import dev.jamesrich.sofaking.backend.catalog.IptvOrgPolicy
import dev.jamesrich.sofaking.backend.catalog.StationKeys
import dev.jamesrich.sofaking.backend.catalog.normalizedName
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.IptvOrgDecision
import dev.jamesrich.sofaking.backend.store.clearIptvOrgDecision
import dev.jamesrich.sofaking.backend.store.iptvOrgDecisions
import dev.jamesrich.sofaking.backend.store.saveIptvOrgDecision
import dev.jamesrich.sofaking.contract.Channel
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** An iptv-org channel a station may be, and why: `name` and `alt-name` matches are never applied unasked. */
@Serializable
data class IptvOrgProposal(
    val station: String,
    val name: String,
    val channelId: String,
    val channelName: String,
    val country: String? = null,
    val basis: String,
    /** More than one channel fits the station equally well. */
    val ambiguous: Boolean = false,
)

/** A station the iptv-org blocklist holds out of the lineup, with the database's reason and reference. */
@Serializable
data class IptvOrgHold(val station: String, val channelId: String, val reason: String? = null, val ref: String? = null)

/** Everything the portal and API show about the matching. */
@Serializable
data class IptvOrgOverview(
    val status: IptvOrgStatus,
    val proposals: List<IptvOrgProposal>,
    val holds: List<IptvOrgHold>,
)

/** A person's choice for one station. */
@Serializable
data class IptvOrgDecisionRequest(val station: String, val decision: String, val channelId: String? = null)

/**
 * Matches lineup channels to iptv-org channels and uses a match for what the source left empty. A match applies on its
 * own only when the channel's `tvg-id` is an iptv-org id, or its name is one iptv-org channel's name in a country the
 * channel is tagged with, or a person accepted it. A name without a country and an alternative name are proposals:
 * they are listed as findings until accepted or dismissed. A matched channel on the blocklist is held out of the
 * lineup with the database's reason and reference, until the match is dismissed or the policy turns the station on.
 */
class IptvOrgMatcher(
    private val catalog: IptvOrgCatalog,
    private val store: BackendStore,
    private val settings: () -> IptvOrgSettings,
    private val clock: Clock,
    private val onDecided: () -> Unit = {},
) : IptvOrgPolicy {
    private class Run(
        val proposals: List<IptvOrgProposal>,
        val holds: List<IptvOrgHold>,
        val matched: Int,
        val names: Map<String, String>,
    )

    private val last = AtomicReference(Run(emptyList(), emptyList(), 0, emptyMap()))

    override suspend fun enrich(candidates: List<FeedCandidate>, keys: StationKeys): IptvOrgEnriched {
        val data = catalog.data
        if (!settings().metadata || data.channels.isEmpty()) {
            last.set(Run(emptyList(), emptyList(), 0, emptyMap()))
            return IptvOrgEnriched(candidates, emptyMap())
        }
        val decisions = store.iptvOrgDecisions().associateBy { it.stationKey }
        val countries = data.channels.mapNotNullTo(HashSet()) { it.country }
        val proposals = LinkedHashMap<String, MutableList<IptvOrgProposal>>()
        val applied = HashMap<String, IptvChannel>()
        val names = HashMap<String, String>()
        val enriched = candidates.map { candidate ->
            val channel = candidate.channel
            val key = keys.key(channel.name)
            names.putIfAbsent(key, channel.name)
            val decision = decisions[key]
            val match: IptvChannel? = when {
                decision?.decision == DISMISSED -> null

                decision?.decision == ACCEPTED -> decision.channelId?.let(data.byId::get)

                else -> {
                    val found = find(data, channel, countries)
                    if (found.matched == null) {
                        val list = proposals.getOrPut(key) { mutableListOf() }
                        found.proposed.forEach { list += it.toProposal(key, channel.name) }
                    }
                    found.matched
                }
            }
            if (match != null) applied[key] = match
            if (match == null) candidate else candidate.copy(channel = fill(channel, match, data))
        }
        val holds = applied.filter { (_, channel) -> channel.id in data.blocklist }.map { (key, channel) ->
            val block = data.blocklist.getValue(channel.id)
            IptvOrgHold(key, channel.id, block.reason, block.ref)
        }.sortedBy { it.station }
        val open = proposals.filterKeys { it !in applied }.values.flatten().distinctBy { it.station to it.channelId }
        last.set(Run(open, holds, applied.size, names))
        return IptvOrgEnriched(enriched, holds.associate { it.station to holdFinding(it) })
    }

    private class Found(val matched: IptvChannel?, val proposed: List<Proposed>)

    private class Proposed(val channel: IptvChannel, val basis: String, val ambiguous: Boolean) {
        fun toProposal(station: String, name: String) = IptvOrgProposal(
            station,
            name,
            channel.id,
            channel.name,
            channel.country,
            basis,
            ambiguous,
        )
    }

    private fun find(data: IptvOrgData, channel: Channel, countries: Set<String>): Found {
        val byTvgId = channel.legacyGuideId?.substringBefore('#')?.let(data.byId::get)
        return if (byTvgId != null) Found(byTvgId, emptyList()) else findByName(data, channel, countries)
    }

    private fun findByName(data: IptvOrgData, channel: Channel, countries: Set<String>): Found {
        val name = normalizedName(channel.name, IptvOrgData.NAME_VERSION)
        val tagged = channel.tags.map { it.uppercase() }.filter { it in countries }.toSet()
        val named = data.byName[name].orEmpty()
        val inCountry = named.filter { it.country in tagged }
        return when {
            inCountry.size == 1 -> Found(inCountry.single(), emptyList())

            inCountry.size > 1 -> Found(null, inCountry.map { Proposed(it, "name", true) })

            named.isNotEmpty() -> Found(null, named.map { Proposed(it, "name", named.size > 1) })

            else -> data.byAltName[name].orEmpty().let { alt ->
                Found(null, alt.map { Proposed(it, "alt-name", alt.size > 1) })
            }
        }
    }

    /** [channel] with the logo and category the source left out taken from [match]. */
    private fun fill(channel: Channel, match: IptvChannel, data: IptvOrgData): Channel {
        val logo = channel.logo ?: data.logos[match.id]
        val tags = channel.tags.ifEmpty {
            listOfNotNull(match.categories.firstOrNull()?.replaceFirstChar(Char::uppercase), match.country?.lowercase())
        }
        return if (logo == channel.logo && tags == channel.tags) channel else channel.copy(logo = logo, tags = tags)
    }

    private fun holdFinding(hold: IptvOrgHold): String {
        val why = listOfNotNull(hold.reason, hold.ref).joinToString(", ").ifEmpty { "no reason given" }
        return "iptv-org blocklist holds this station out as ${hold.channelId} ($why); " +
            "dismiss the iptv-org match or turn the station on in the policy to list it"
    }

    override fun findings(): List<String> = last.get().proposals.groupBy { it.station }.map { (station, list) ->
        val shown = list.take(FINDING_CANDIDATES).joinToString(", ") { "${it.channelId} (${it.basis})" }
        val more = if (list.size > FINDING_CANDIDATES) ", and more" else ""
        "$station: iptv-org candidates $shown$more; accept or dismiss"
    }.sorted()

    /** The status of the downloads with the matching counts, the open proposals and the holds. */
    fun overview(): IptvOrgOverview {
        val run = last.get()
        return IptvOrgOverview(
            catalog.status().copy(
                matched = run.matched,
                proposals = run.proposals.map {
                    it.station
                }.distinct().size,
                held = run.holds.size,
            ),
            run.proposals,
            run.holds,
        )
    }

    /** Records the person's choice for a station, then asks for a policy run. */
    suspend fun decide(request: IptvOrgDecisionRequest) {
        val station = request.station.trim()
        if (station.isEmpty()) throw bad("station is required")
        when (request.decision) {
            "accept" -> {
                val id = request.channelId ?: throw bad("channelId is required to accept")
                if (catalog.data.byId[id] == null) {
                    throw ApiFailure(HttpStatusCode.NotFound, "IPTV_ORG_CHANNEL_NOT_FOUND", "Unknown iptv-org channel")
                }
                store.saveIptvOrgDecision(IptvOrgDecision(station, id, ACCEPTED), clock.instant())
            }

            "dismiss" -> store.saveIptvOrgDecision(IptvOrgDecision(station, null, DISMISSED), clock.instant())

            "clear" -> store.clearIptvOrgDecision(station)

            else -> throw bad("decision must be accept, dismiss or clear")
        }
        onDecided()
    }

    private fun bad(message: String) = ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", message)

    companion object {
        private const val ACCEPTED = "accepted"
        private const val DISMISSED = "dismissed"
        private const val FINDING_CANDIDATES = 3
    }
}
