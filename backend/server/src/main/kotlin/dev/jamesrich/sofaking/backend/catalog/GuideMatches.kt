package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.api.GuideMatch
import dev.jamesrich.sofaking.backend.api.GuideMatchChoice
import dev.jamesrich.sofaking.backend.api.GuideMatchResult
import dev.jamesrich.sofaking.backend.api.PolicyApplyResult
import dev.jamesrich.sofaking.backend.api.PolicyGuideMatches
import dev.jamesrich.sofaking.backend.api.PolicyStation
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The lowest confidence a proposal needs to be listed. */
private const val PROPOSAL_FLOOR = 0.6

/** A name that differs is never as sure as one that matches once cleaned. */
private const val FUZZY_CEILING = 0.95

/** Proposals listed per station, best first. */
private const val PROPOSALS_PER_STATION = 3

/**
 * Guide channels proposed by name for the stations of the last apply that no guide lists, and the choices a person
 * makes about them: accepting writes the station's `guide`, dismissing lists the pair in `guideMatches.dismissed`.
 */
class GuideMatches internal constructor(private val service: PolicyService, private val guides: GuideService) {
    /** Each station's proposals, in lineup order, best first; empty until an apply has run. */
    fun proposals(): List<GuideMatch> {
        val stations = service.lastApplied()?.stations.orEmpty().filter { it.guide == null }
        if (stations.isEmpty()) return emptyList()
        val settings = service.policy.guideMatches
        val dismissed = settings.dismissed.toSet()
        val matcher = GuideNameMatcher(settings, guides.configs.associate { it.id to guides.channels(it.id) })
        return stations.flatMap { station ->
            matcher.matches(station.name)
                .filter { GuideMatchChoice(station.key, it.guideId, it.channel.id) !in dismissed }
                .take(PROPOSALS_PER_STATION)
                .map { it.toMatch(station) }
        }
    }

    /** Writes the proposed channel as the station's guide, then applies. */
    suspend fun accept(choice: GuideMatchChoice): PolicyApplyResult {
        val channel = guides.channel(choice.guideId, choice.channelId)
            ?: throw ApiFailure(HttpStatusCode.NotFound, "GUIDE_CHANNEL_NOT_FOUND", "Unknown guide channel")
        service.file.edit(null) { tree -> withGuide(tree, choice.station, GuideMapping(choice.guideId, channel.id)) }
        return service.apply("guide-match")
    }

    /**
     * Writes each station's best proposal at [minConfidence] or more (else the policy's `acceptAbove`) in one edit,
     * then applies once. A station whose best two proposals tie on different channels is left for a person.
     */
    suspend fun acceptAll(minConfidence: Double?): GuideMatchResult {
        val floor = minConfidence ?: service.policy.guideMatches.acceptAbove
        if (floor !in 0.0..1.0) {
            throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "minConfidence must be between 0 and 1")
        }
        val chosen = proposals().groupBy { it.station }.values.mapNotNull { best ->
            val (first, second) = best[0] to best.getOrNull(1)
            val tied = second != null && second.confidence == first.confidence && second.channelId != first.channelId
            first.takeIf { it.confidence >= floor && !tied }
        }
        if (chosen.isEmpty()) return GuideMatchResult(0)
        service.file.edit(null) { tree ->
            chosen.fold(tree) { next, match ->
                withGuide(next, match.station, GuideMapping(match.guideId, match.channelId))
            }
        }
        return GuideMatchResult(chosen.size, service.apply("guide-match"))
    }

    /** Lists the pair in `guideMatches.dismissed` so it is never proposed again, then applies. */
    suspend fun dismiss(choice: GuideMatchChoice): PolicyApplyResult {
        service.file.edit(null) { tree ->
            val settings = (tree["guideMatches"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            val dismissed = (settings["dismissed"] as? JsonArray).orEmpty().toMutableList()
            val entry = Json.encodeToJsonElement(GuideMatchChoice.serializer(), choice)
            if (entry !in dismissed) dismissed += entry
            settings["dismissed"] = JsonArray(dismissed)
            JsonObject(tree.toMutableMap().apply { put("guideMatches", JsonObject(settings)) })
        }
        return service.apply("guide-match")
    }
}

private fun withGuide(tree: JsonObject, key: String, mapping: GuideMapping): JsonObject = editStation(tree, key) {
    it["guide"] = Json.encodeToJsonElement(GuideMapping.serializer(), mapping)
}

/** One guide channel a name matched, with how sure the match is. */
internal class ScoredChannel(val guideId: String, val channel: GuideChannel, val confidence: Double) {
    fun toMatch(station: PolicyStation) = GuideMatch(
        station.key,
        station.id,
        station.name,
        station.number,
        guideId,
        channel.id,
        channel.displayName,
        confidence,
    )
}

/**
 * Matches station names against the channels of each guide (in [channels]' order): both sides lose the ignored
 * prefixes and suffixes, then compare in their loose form. Equal loose names score 1; others that share a word
 * score their letter-pair similarity, at most [FUZZY_CEILING]. A channel's ID before its first `.` counts as a
 * second name for it, as in `CNN.us`.
 */
internal class GuideNameMatcher(settings: PolicyGuideMatches, channels: Map<String, List<GuideChannel>>) {
    private val prefixes = settings.ignorePrefixes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    private val suffixes = settings.ignoreSuffixes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private class Named(val guideId: String, val channel: GuideChannel, val form: MatchForm)

    private val named: List<Named> = channels.flatMap { (guideId, list) ->
        list.flatMap { channel ->
            listOfNotNull(form(channel.displayName), form(channel.id.substringBefore('.')))
                .distinctBy { it.loose }
                .map { Named(guideId, channel, it) }
        }
    }
    private val exact: Map<String, List<Named>> = named.groupBy { it.form.loose }
    private val byWord: Map<String, List<Named>> = buildMap<String, MutableList<Named>> {
        for (entry in named) for (word in entry.form.words) getOrPut(word) { mutableListOf() } += entry
    }

    /** The channels [name] matches at [PROPOSAL_FLOOR] or more, best first, one per guide channel. */
    fun matches(name: String): List<ScoredChannel> {
        val station = form(name) ?: return emptyList()
        val scored = HashMap<Pair<String, String>, ScoredChannel>()
        fun offer(entry: Named, confidence: Double) {
            val id = entry.guideId to entry.channel.id
            if (confidence >= (scored[id]?.confidence ?: PROPOSAL_FLOOR)) {
                scored[id] = ScoredChannel(entry.guideId, entry.channel, confidence)
            }
        }
        for (entry in exact[station.loose].orEmpty()) offer(entry, 1.0)
        val shared = station.words.flatMapTo(LinkedHashSet()) { byWord[it].orEmpty() }
        for (entry in shared) {
            if (entry.form.loose != station.loose) offer(entry, FUZZY_CEILING * dice(station.pairs, entry.form.pairs))
        }
        val order = channelsOrder
        return scored.values.sortedWith(
            compareByDescending<ScoredChannel> { it.confidence }.thenBy { order[it.guideId] ?: Int.MAX_VALUE },
        )
    }

    private val channelsOrder: Map<String, Int> = channels.keys.withIndex().associate { it.value to it.index }

    /** [name] without an ignored prefix and suffix, in loose form; null when nothing is left. */
    internal fun form(name: String): MatchForm? {
        var text = name.trim()
        prefixes.firstOrNull { text.lowercase().startsWith(it) }?.let { text = text.substring(it.length).trim() }
        suffixes.firstOrNull { text.lowercase().endsWith(it) }?.let { text = text.dropLast(it.length).trim() }
        val words = baseName(text).split(' ').filter { it.isNotEmpty() && it !in LOOSE_FILLER }
        val loose = looseName(text) ?: normalizedName(text, 2).ifEmpty { return null }
        return MatchForm(loose, words.toSet(), letterPairs(loose))
    }
}

/** A name compared loosely: [loose] joined, its [words], and the letter pairs of [loose]. */
internal class MatchForm(val loose: String, val words: Set<String>, val pairs: List<String>)

private fun letterPairs(text: String): List<String> = if (text.length < 2) listOf(text) else text.windowed(2)

/** The Sørensen–Dice similarity of two lists of letter pairs, counting repeats: 0 to 1. */
private fun dice(a: List<String>, b: List<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val counts = b.groupingBy { it }.eachCountTo(HashMap())
    var shared = 0
    for (pair in a) {
        val left = counts[pair] ?: 0
        if (left > 0) {
            shared++
            counts[pair] = left - 1
        }
    }
    return 2.0 * shared / (a.size + b.size)
}
