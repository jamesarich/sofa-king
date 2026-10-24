package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.CandidateFilter
import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.SourcePin
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.regex.PatternSyntaxException

private val policyJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    prettyPrint = true
}

/** A policy as read from disk: the parsed document and the SHA-256 of its bytes. */
class LoadedPolicy(val path: Path, val policy: LineupPolicy, val revision: String)

/** Reads and parses [path]; unknown keys fail. The file is never variable-expanded. */
fun readPolicy(path: Path): LoadedPolicy {
    require(Files.size(path) <= POLICY_LIMIT) { "Lineup policy is too large" }
    val bytes = Files.readAllBytes(path)
    return LoadedPolicy(path, parsePolicy(bytes), policyRevision(bytes))
}

internal const val POLICY_LIMIT = 4 * 1_048_576

/** Parses policy bytes; unknown keys fail. */
internal fun parsePolicy(bytes: ByteArray): LineupPolicy =
    policyJson.decodeFromString<LineupPolicy>(bytes.toString(Charsets.UTF_8))

/** The SHA-256 of the file bytes, hex. */
internal fun policyRevision(bytes: ByteArray): String = hash(bytes)

/** Reads [path] and fails with every validation error at once. */
fun loadPolicy(path: Path, providerIds: Set<String>): LoadedPolicy {
    val loaded = readPolicy(path)
    val errors = validatePolicy(loaded.policy, providerIds)
    require(errors.isEmpty()) { "Invalid lineup policy $path:\n" + errors.joinToString("\n") { "  - $it" } }
    return loaded
}

/** The station number grammar provider numbers must follow to be kept. */
internal val STATION_NUMBER = Regex("\\d{1,9}(\\.\\d{1,4})?")

/** [number] without leading zeros in its integer part, so `0015` and `15` collide; other values unchanged. */
internal fun canonicalNumber(number: String): String {
    if (!number.matches(STATION_NUMBER)) return number
    val integer = number.substringBefore('.').trimStart('0').ifEmpty { "0" }
    return if ('.' in number) integer + "." + number.substringAfter('.') else integer
}

/** Every problem with [policy], against the configured [providerIds]; empty when it is valid. */
fun validatePolicy(policy: LineupPolicy, providerIds: Set<String>): List<String> {
    val errors = ArrayList<String>()
    if (policy.version != 1) errors += "version must be 1"
    if (policy.names.version !in NAME_VERSIONS) errors += "names.version must be one of ${NAME_VERSIONS.sorted()}"
    if (policy.maxSourcesPerStation !in 1..64) errors += "maxSourcesPerStation must be between 1 and 64"
    if (policy.numbering.floor !in 1..999_999) errors += "numbering.floor must be between 1 and 999999"
    if (policy.numbering.groupSize !in 1..100_000) errors += "numbering.groupSize must be between 1 and 100000"
    for ((group, start) in policy.numbering.groups.entries.sortedBy { it.key }) {
        if (group.isBlank() || group.length > 128) {
            errors += "numbering.groups: group \"$group\" must be 1 to 128 characters"
        }
        if (start !in 1..999_999) errors += "numbering.groups[\"$group\"]: start must be between 1 and 999999"
    }
    if (policy.health.disableAfterHours !in 1..720) errors += "health.disableAfterHours must be between 1 and 720"
    if (policy.health.enableOn !in setOf("healthy", "eligible")) errors += "health.enableOn must be healthy or eligible"
    if (policy.health.keepMissingDays !in 0..3650) errors += "health.keepMissingDays must be between 0 and 3650"

    fun pattern(value: String, where: String) {
        if (value.isBlank() || value.length > 256) {
            errors += "$where: patterns must be 1 to 256 characters"
            return
        }
        try {
            Regex(value)
        } catch (e: PatternSyntaxException) {
            errors +=
                "$where: invalid pattern ${e.message?.lineSequence()?.firstOrNull()}"
        }
    }
    fun filter(value: CandidateFilter?, where: String) {
        value ?: return
        value.groupPatterns.forEach { pattern(it, "$where.groupPatterns") }
        value.namePatterns.forEach { pattern(it, "$where.namePatterns") }
    }
    policy.providers.forEachIndexed { index, entry ->
        val where = "providers[$index]"
        if (entry.id !in providerIds) errors += "$where: unknown provider ${entry.id}"
        filter(entry.include, "$where.include")
        filter(entry.exclude, "$where.exclude")
        entry.group?.let { if (it.isBlank() || it.length > 128) errors += "$where: group must be 1 to 128 characters" }
    }
    policy.groups.patterns.forEachIndexed { index, item ->
        val where = "groups.patterns[$index]"
        if (item.name == null && item.rawGroup == null) errors += "$where: needs name or rawGroup"
        item.name?.let { pattern(it, "$where.name") }
        item.rawGroup?.let { pattern(it, "$where.rawGroup") }
        if (item.group.isBlank() || item.group.length > 128) errors += "$where: group must be 1 to 128 characters"
    }
    for ((raw, group) in policy.groups.map.entries.sortedBy { it.key }) {
        if (raw != raw.trim().lowercase()) errors += "groups.map: key \"$raw\" must be lowercase and trimmed"
        if (group.isBlank() || group.length > 128) errors += "groups.map[\"$raw\"]: group must be 1 to 128 characters"
    }
    for (raw in policy.groups.weak) {
        if (raw != raw.trim().lowercase()) {
            errors += "groups.weak: \"$raw\" must be lowercase and trimmed"
        }
    }

    val aliases = policy.names.aliases
    for ((from, to) in aliases.entries.sortedBy { it.key }) {
        if (from == to) {
            errors += "names.aliases: \"$from\" aliases itself"
        } else if (to in aliases) {
            errors += "names.aliases: \"$from\" -> \"$to\" chains through another alias"
        }
        val normalized = normalizedName(from, policy.names.version)
        if (normalized != from) {
            errors += "names.aliases: \"$from\" is not a normalized name (it normalizes to \"$normalized\")"
        }
    }
    val learned = policy.names.learned
    for ((from, to) in learned.entries.sortedBy { it.key }) {
        if (from == to) {
            errors += "names.learned: \"$from\" aliases itself"
        } else if (to in learned) {
            errors += "names.learned: \"$from\" -> \"$to\" chains through another learned alias"
        }
    }
    policy.names.never.forEachIndexed { index, pair ->
        if (pair.size != 2 || pair.any { it.isBlank() } || pair[0] == pair[1]) {
            errors += "names.never[$index]: must be two different keys"
        }
    }
    val effective = effectiveLearned(policy.names)

    val numbers = HashMap<String, MutableList<String>>()
    val ids = HashMap<String, MutableList<String>>()
    val pins = HashMap<String, MutableList<String>>()
    for ((key, station) in policy.stations.entries.sortedBy { it.key }) {
        val where = "stations[\"$key\"]"
        if (key in aliases) {
            errors += "$where: key is an alias of \"${aliases.getValue(key)}\"; use the post-alias key"
        } else if (key in
            effective
        ) {
            errors +=
                "$where: key is a learned alias of \"${effective.getValue(key)}\"; use the post-alias key"
        }
        if (key.isBlank() || key.length > 256) errors += "$where: key must be 1 to 256 characters"
        station.number?.let { number ->
            if (number.isBlank() || number.length > 32 || number.any { it.isWhitespace() }) {
                errors += "$where: number must be 1 to 32 characters without spaces"
            }
            numbers.getOrPut(canonicalNumber(number)) { mutableListOf() } += key
        }
        station.id?.let { id ->
            val parsed = runCatching { UUID.fromString(id).toString() }.getOrNull()
            if (parsed == null || parsed != id.lowercase()) errors += "$where: id must be a UUID"
            ids.getOrPut(id.lowercase()) { mutableListOf() } += key
        }
        station.name?.let { if (it.isBlank() || it.length > 256) errors += "$where: name must be 1 to 256 characters" }
        station.group?.let {
            if (it.isBlank() || it.length > 128) {
                errors += "$where: group must be 1 to 128 characters"
            }
        }
        station.logo?.let { if (normalizeLogoUrl(it) == null) errors += "$where: logo must be an HTTP(S) URL" }
        for (provider in station.prefer + station.exclude) {
            if (provider !in
                providerIds
            ) {
                errors += "$where: unknown provider $provider in prefer/exclude"
            }
        }
        for (pin in station.sources) {
            if (pin.provider !in providerIds) errors += "$where: pin names unknown provider ${pin.provider}"
            if (pin.tvgId == null && pin.name == null) errors += "$where: a pin needs tvgId or name"
            pins.getOrPut(pinIdentity(pin)) { mutableListOf() } += key
        }
    }
    for ((number, keys) in numbers.entries.sortedBy { it.key }) {
        if (keys.size > 1) {
            errors += "number $number is claimed by ${keys.joinToString()}"
        }
    }
    for ((id, keys) in ids.entries.sortedBy { it.key }) {
        val generated = policy.stations.keys.sorted().firstOrNull {
            it !in keys && policy.stations.getValue(it).id == null && stationId(it) == id
        }
        if (generated != null) errors += "id $id is the generated id of \"$generated\""
    }
    for ((id, keys) in ids.entries.sortedBy { it.key }) {
        if (keys.size > 1) {
            errors += "id $id is claimed by ${keys.joinToString()}"
        }
    }
    for ((pin, keys) in pins.entries.sortedBy { it.key }) {
        if (keys.size > 1) {
            errors +=
                "pin $pin is claimed by " +
                "${keys.distinct().joinToString()}${if (keys.distinct().size == 1) " more than once" else ""}"
        }
    }
    return errors
}

internal fun pinIdentity(pin: SourcePin): String = if (pin.tvgId != null) {
    "${pin.provider}/tvg-id:${pin.tvgId}"
} else {
    "${pin.provider}/name:${pin.name?.trim()?.lowercase()}"
}

/** The ID a station without an `id` override carries. */
fun stationId(key: String): String = stableId("station:$key")
