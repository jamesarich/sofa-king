package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.LineupPolicy
import dev.jamesrich.sofaking.backend.api.SourcePin
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

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

internal fun pinIdentity(pin: SourcePin): String = if (pin.tvgId != null) {
    "${pin.provider}/tvg-id:${pin.tvgId}"
} else {
    "${pin.provider}/name:${pin.name?.trim()?.lowercase()}"
}

/** The ID a station without an `id` override carries. */
fun stationId(key: String): String = stableId("station:$key")
