package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val idPattern = Regex("[a-zA-Z0-9_-]{1,64}")

private fun validateHeaders(headers: Map<String, String>) {
    require(headers.all { (key, value) -> key.matches(Regex("[A-Za-z0-9-]+")) && value.none { it == '\r' || it == '\n' } })
    require(headers.keys.none { it.lowercase() in setOf("host", "content-length", "connection", "transfer-encoding") })
}

/**
 * One provider playlist. A `guideUrl` is an implicit guide source with this provider's id.
 * [renewableQuery] replaces [DEFAULT_RENEWABLE_QUERY] for this provider's stream URLs.
 */
@Serializable data class FeedConfig(
    val id: String, val playlistUrl: String, val guideUrl: String? = null,
    val headers: Map<String, String> = emptyMap(), val refreshSeconds: Long = 900,
    val allowEmpty: Boolean = false, val probe: Boolean = true, val defaultProtocol: String? = null,
    val renewableQuery: List<String>? = null,
) {
    /** Lowercase query parameter names dropped from this provider's source identities. */
    val renewable: Set<String> get() = renewableQuery(renewableQuery)

    fun validate() {
        require(id.matches(idPattern)) { "Invalid provider ID" }
        httpUri(playlistUrl); guideUrl?.let(::httpUri)
        require(defaultProtocol == null || defaultProtocol in setOf("hls", "mpegts")) { "Unsupported default protocol" }
        require(refreshSeconds >= 60) { "Refresh interval must be at least 60 seconds" }
        validateHeaders(headers)
        renewableQuery?.let(::validateRenewableQuery)
    }
}

/** One XMLTV guide source, plain or gzip. */
@Serializable data class GuideConfig(
    val id: String, val url: String, val headers: Map<String, String> = emptyMap(), val refreshSeconds: Long = 21_600,
) {
    fun validate() {
        require(id.matches(idPattern)) { "Invalid guide ID" }
        httpUri(url)
        require(refreshSeconds >= 300) { "Guide refresh interval must be at least 300 seconds" }
        validateHeaders(headers)
    }
}

data class SourceConfig(val providers: List<FeedConfig> = emptyList(), val guides: List<GuideConfig> = emptyList()) {
    /** Explicit guides first, then one implicit guide per provider `guideUrl`. */
    val allGuides: List<GuideConfig> get() = guides + providers.filter { it.guideUrl != null }.map {
        GuideConfig(it.id, it.guideUrl!!, it.headers, it.refreshSeconds.coerceAtLeast(300))
    }
}

@Serializable private data class SourceFile(val providers: List<FeedConfig> = emptyList(), val guides: List<GuideConfig> = emptyList())

private val configJson = Json { ignoreUnknownKeys = false }

/**
 * Reads the source file: a JSON array of providers, or an object with `providers` and `guides`.
 * `${NAME}` inside any string is replaced from [environment], so tokens stay out of the file.
 */
fun loadSources(path: Path?, environment: Map<String, String> = System.getenv()): SourceConfig {
    if (path == null) return SourceConfig()
    require(Files.size(path) <= 1_048_576) { "Source configuration is too large" }
    val element = expandVariables(configJson.parseToJsonElement(Files.readString(path)), environment)
    val file = when (element) {
        is JsonArray -> SourceFile(providers = configJson.decodeFromJsonElement(element))
        is JsonObject -> configJson.decodeFromJsonElement<SourceFile>(element)
        else -> throw IllegalArgumentException("Source configuration must be a JSON array or object")
    }
    val config = SourceConfig(file.providers, file.guides)
    require(config.providers.map { it.id }.distinct().size == config.providers.size) { "Duplicate provider IDs" }
    config.providers.forEach { it.validate() }
    config.guides.forEach { it.validate() }
    require(config.allGuides.map { it.id }.distinct().size == config.allGuides.size) { "Duplicate guide IDs (a provider guideUrl uses the provider ID)" }
    return config
}

fun loadFeeds(path: Path?): List<FeedConfig> = loadSources(path).providers

private val variable = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")

internal fun expandVariables(element: JsonElement, environment: Map<String, String>): JsonElement = when (element) {
    is JsonPrimitive -> if (element.isString) {
        JsonPrimitive(variable.replace(element.content) { match ->
            environment[match.groupValues[1]] ?: throw IllegalArgumentException("Source configuration references unset variable ${match.groupValues[1]}")
        })
    } else {
        element
    }
    is JsonArray -> JsonArray(element.map { expandVariables(it, environment) })
    is JsonObject -> JsonObject(element.mapValues { expandVariables(it.value, environment) })
}

fun httpUri(value: String): URI = URI(value).also {
    require(it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null) { "Expected an HTTP(S) URL without user info" }
}
@Serializable data class Offering(val id: String, val channelId: String, val providerId: String, val url: String, val protocol: String)
/** `programmes` is kept for decoding snapshots written before guides became independent sources; it is no longer populated. */
@Serializable data class FeedSnapshot(val providerId: String, val channels: List<Channel>, val offerings: List<Offering>, val programmes: List<Programme> = emptyList(), val importedAt: String)
fun stableId(value: String): String = java.util.UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

/** An XMLTV `<channel>`, or a channel id seen only on programmes (then [displayName] is the id). */
data class GuideChannel(val id: String, val displayName: String, val icon: String? = null)
data class GuideProgramme(val channelId: String, val startsAt: Instant, val endsAt: Instant, val title: String, val description: String? = null)

/**
 * A household channel: the app sees these, in this order, and nothing else. [offeringIds] is the failover order.
 * [pinned] names the fields the household set ([PINNABLE_FIELDS]), which automation never changes; [autoDisabledAt]
 * is set while automation, not the household, holds the entry disabled.
 */
data class LineupEntryRecord(
    val id: String, val number: String, val name: String, val group: String? = null, val logo: String? = null,
    val enabled: Boolean = true, val guide: GuideMapping? = null, val offeringIds: List<String> = emptyList(),
    val pinned: Set<String> = emptySet(), val createdBy: String = "manual",
    val autoDisabledAt: Instant? = null, val autoDisabledReason: String? = null,
) {
    fun pinned(field: String): Boolean = field in pinned
}

val PINNABLE_FIELDS: Set<String> = setOf("number", "name", "group", "logo", "enabled", "offerings", "guide")
