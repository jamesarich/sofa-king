package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.CandidateFilter
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.StationOverride
import java.util.UUID
import java.util.regex.PatternSyntaxException

private val GROUP_SIZES = 1..100_000

/** Up to 30 days; a source unhealthy longer than that is disabled. */
private val DISABLE_AFTER_HOURS = 1..720
private val KEEP_MISSING_DAYS = 0..3650
private val ENABLE_ON = setOf("healthy", "eligible")

/** "first and last", as validation messages name a range. */
private val IntRange.span: String get() = "$first and $last"

/**
 * Every problem with [policy], against the configured [providerIds]; empty when it is valid. The problems come in
 * the policy's order: its settings, providers, groups, names and stations, then what two stations both claim.
 */
fun validatePolicy(policy: LineupPolicy, providerIds: Set<String>): List<String> {
    val effective = effectiveLearned(policy.names)
    return settingErrors(policy) + providerErrors(policy, providerIds) + groupErrors(policy) + nameErrors(policy) +
        policy.stations.entries.sortedBy { it.key }.flatMap { (key, station) ->
            stationErrors(key, station, policy, effective, providerIds)
        } +
        claimErrors(policy)
}

private fun settingErrors(policy: LineupPolicy): List<String> = buildList {
    if (policy.version != 1) add("version must be 1")
    if (policy.names.version !in NAME_VERSIONS) add("names.version must be one of ${NAME_VERSIONS.sorted()}")
    if (policy.maxSourcesPerStation !in 1..SOURCES_PER_STATION_LIMIT) {
        add("maxSourcesPerStation must be between 1 and $SOURCES_PER_STATION_LIMIT")
    }
    val numbering = policy.numbering
    if (numbering.floor !in CHANNEL_NUMBERS) add("numbering.floor must be between ${CHANNEL_NUMBERS.span}")
    if (numbering.groupSize !in GROUP_SIZES) add("numbering.groupSize must be between ${GROUP_SIZES.span}")
    for ((group, start) in numbering.groups.entries.sortedBy { it.key }) {
        if (!fits(group, GROUP_LIMIT)) add("numbering.groups: group \"$group\" must be 1 to $GROUP_LIMIT characters")
        if (start !in CHANNEL_NUMBERS) {
            add("numbering.groups[\"$group\"]: start must be between ${CHANNEL_NUMBERS.span}")
        }
    }
    val health = policy.health
    if (health.disableAfterHours !in DISABLE_AFTER_HOURS) {
        add("health.disableAfterHours must be between ${DISABLE_AFTER_HOURS.span}")
    }
    if (health.enableOn !in ENABLE_ON) add("health.enableOn must be healthy or eligible")
    if (health.keepMissingDays !in KEEP_MISSING_DAYS) {
        add("health.keepMissingDays must be between ${KEEP_MISSING_DAYS.span}")
    }
}

private fun patternErrors(value: String, where: String): List<String> {
    if (!fits(value, NAME_LIMIT)) return listOf("$where: patterns must be 1 to $NAME_LIMIT characters")
    return try {
        Regex(value)
        emptyList()
    } catch (e: PatternSyntaxException) {
        listOf("$where: invalid pattern ${e.message?.lineSequence()?.firstOrNull()}")
    }
}

private fun filterErrors(value: CandidateFilter?, where: String): List<String> =
    value?.groupPatterns.orEmpty().flatMap { patternErrors(it, "$where.groupPatterns") } +
        value?.namePatterns.orEmpty().flatMap { patternErrors(it, "$where.namePatterns") }

private fun providerErrors(policy: LineupPolicy, providerIds: Set<String>): List<String> =
    policy.providers.withIndex().flatMap { (index, entry) ->
        val where = "providers[$index]"
        buildList {
            if (entry.id !in providerIds) add("$where: unknown provider ${entry.id}")
            addAll(filterErrors(entry.include, "$where.include"))
            addAll(filterErrors(entry.exclude, "$where.exclude"))
            if (entry.group?.let { fits(it, GROUP_LIMIT) } == false) {
                add("$where: group must be 1 to $GROUP_LIMIT characters")
            }
        }
    }

private fun groupErrors(policy: LineupPolicy): List<String> = buildList {
    policy.groups.patterns.forEachIndexed { index, item ->
        val where = "groups.patterns[$index]"
        if (item.name == null && item.rawGroup == null) add("$where: needs name or rawGroup")
        item.name?.let { addAll(patternErrors(it, "$where.name")) }
        item.rawGroup?.let { addAll(patternErrors(it, "$where.rawGroup")) }
        if (!fits(item.group, GROUP_LIMIT)) add("$where: group must be 1 to $GROUP_LIMIT characters")
    }
    for ((raw, group) in policy.groups.map.entries.sortedBy { it.key }) {
        if (raw != raw.trim().lowercase()) add("groups.map: key \"$raw\" must be lowercase and trimmed")
        if (!fits(group, GROUP_LIMIT)) add("groups.map[\"$raw\"]: group must be 1 to $GROUP_LIMIT characters")
    }
    for (raw in policy.groups.weak) {
        if (raw != raw.trim().lowercase()) add("groups.weak: \"$raw\" must be lowercase and trimmed")
    }
}

private fun nameErrors(policy: LineupPolicy): List<String> = buildList {
    val aliases = policy.names.aliases
    for ((from, to) in aliases.entries.sortedBy { it.key }) {
        if (from == to) {
            add("names.aliases: \"$from\" aliases itself")
        } else if (to in aliases) {
            add("names.aliases: \"$from\" -> \"$to\" chains through another alias")
        }
        val normalized = normalizedName(from, policy.names.version)
        if (normalized != from) {
            add("names.aliases: \"$from\" is not a normalized name (it normalizes to \"$normalized\")")
        }
    }
    val learned = policy.names.learned
    for ((from, to) in learned.entries.sortedBy { it.key }) {
        if (from == to) {
            add("names.learned: \"$from\" aliases itself")
        } else if (to in learned) {
            add("names.learned: \"$from\" -> \"$to\" chains through another learned alias")
        }
    }
    policy.names.never.forEachIndexed { index, pair ->
        if (pair.size != 2 || pair.any { it.isBlank() } || pair[0] == pair[1]) {
            add("names.never[$index]: must be two different keys")
        }
    }
}

/** The problems with the override of station [key]; [effective] holds the learned aliases in effect. */
private fun stationErrors(
    key: String,
    station: StationOverride,
    policy: LineupPolicy,
    effective: Map<String, String>,
    providerIds: Set<String>,
): List<String> = buildList {
    val where = "stations[\"$key\"]"
    val aliases = policy.names.aliases
    if (key in aliases) {
        add("$where: key is an alias of \"${aliases.getValue(key)}\"; use the post-alias key")
    } else if (key in effective) {
        add("$where: key is a learned alias of \"${effective.getValue(key)}\"; use the post-alias key")
    }
    if (!fits(key, NAME_LIMIT)) add("$where: key must be 1 to $NAME_LIMIT characters")
    addAll(overrideErrors(where, station))
    for (provider in station.prefer + station.exclude) {
        if (provider !in providerIds) add("$where: unknown provider $provider in prefer/exclude")
    }
    for (pin in station.sources) {
        if (pin.provider !in providerIds) add("$where: pin names unknown provider ${pin.provider}")
        if (pin.tvgId == null && pin.name == null) add("$where: a pin needs tvgId or name")
    }
}

/** The problems with the values a station override sets, reported at [where]. */
private fun overrideErrors(where: String, station: StationOverride): List<String> = buildList {
    val number = station.number
    if (number != null && (!fits(number, NUMBER_LIMIT) || number.any { it.isWhitespace() })) {
        add("$where: number must be 1 to $NUMBER_LIMIT characters without spaces")
    }
    val id = station.id
    if (id != null && runCatching { UUID.fromString(id).toString() }.getOrNull() != id.lowercase()) {
        add("$where: id must be a UUID")
    }
    if (station.name?.let { fits(it, NAME_LIMIT) } == false) add("$where: name must be 1 to $NAME_LIMIT characters")
    if (station.group?.let { fits(it, GROUP_LIMIT) } == false) {
        add("$where: group must be 1 to $GROUP_LIMIT characters")
    }
    if (station.logo != null && normalizeLogoUrl(station.logo) == null) add("$where: logo must be an HTTP(S) URL")
    if (station.gracenoteId != null && !station.gracenoteId.matches(GRACENOTE_ID)) {
        add("$where: gracenoteId must be 1 to 10 digits")
    }
}

/** A Gracenote (TMS) station id, as Channels DVR and Zap2it list it. */
internal val GRACENOTE_ID = Regex("[0-9]{1,10}")

/**
 * What more than one station claims: a channel number, a station id, or a source pin; and an id override that is
 * another station's generated id.
 */
private fun claimErrors(policy: LineupPolicy): List<String> {
    val stations = policy.stations.entries.sortedBy { it.key }
    val numbers = stations.mapNotNull { (key, station) -> station.number?.let { canonicalNumber(it) to key } }
        .groupBy({ it.first }, { it.second })
    val ids = stations.mapNotNull { (key, station) -> station.id?.let { it.lowercase() to key } }
        .groupBy({ it.first }, { it.second })
    val pins = stations.flatMap { (key, station) -> station.sources.map { pinIdentity(it) to key } }
        .groupBy({ it.first }, { it.second })
    val generated = ids.entries.sortedBy { it.key }.mapNotNull { (id, keys) ->
        policy.stations.keys.sorted().firstOrNull {
            it !in keys && policy.stations.getValue(it).id == null && stationId(it) == id
        }?.let { "id $id is the generated id of \"$it\"" }
    }
    return numbers.entries.sortedBy { it.key }.filter { it.value.size > 1 }.map { (number, keys) ->
        "number $number is claimed by ${keys.joinToString()}"
    } + generated +
        ids.entries.sortedBy { it.key }.filter { it.value.size > 1 }.map { (id, keys) ->
            "id $id is claimed by ${keys.joinToString()}"
        } +
        pins.entries.sortedBy { it.key }.filter { it.value.size > 1 }.map { (pin, keys) ->
            val once = if (keys.distinct().size == 1) " more than once" else ""
            "pin $pin is claimed by ${keys.distinct().joinToString()}$once"
        }
}
