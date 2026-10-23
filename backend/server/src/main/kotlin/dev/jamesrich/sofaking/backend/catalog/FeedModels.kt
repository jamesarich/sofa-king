package dev.jamesrich.sofaking.backend.catalog

import dev.jamesrich.sofaking.backend.api.GuideMapping
import dev.jamesrich.sofaking.backend.providers.NativeServices
import dev.jamesrich.sofaking.contract.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private val idPattern = Regex("[a-zA-Z0-9_-]{1,64}")
private val headerName = Regex("[A-Za-z0-9-]+")
private val optionKey = Regex("[A-Za-z0-9_-]{1,64}")
private val reservedHeaders = setOf("host", "content-length", "connection", "transfer-encoding")

private fun validateHeaders(headers: Map<String, String>) {
    require(headers.all { (key, value) -> key.matches(headerName) && value.none { it == '\r' || it == '\n' } })
    require(headers.keys.none { it.lowercase() in reservedHeaders })
}

/**
 * One provider: an M3U playlist (`type` `m3u`, the default) or a native service client (`type` `native` with
 * `service`, see [NativeServices]). A `guideUrl` is an implicit guide source with this provider's id; a native
 * service with a guide of its own gets one without it. [renewableQuery] replaces [DEFAULT_RENEWABLE_QUERY]
 * for this provider's stream URLs. [refreshSeconds] unset means 900 for a playlist and 3600 for a service.
 */
@Serializable data class FeedConfig(
    val id: String,
    val playlistUrl: String? = null,
    val guideUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val refreshSeconds: Long? = null,
    val allowEmpty: Boolean = false,
    val probe: Boolean = true,
    val defaultProtocol: String? = null,
    val renewableQuery: List<String>? = null,
    val type: String = "m3u",
    val service: String? = null,
    val options: Map<String, String> = emptyMap(),
) {
    /** Lowercase query parameter names dropped from this provider's source identities. */
    val renewable: Set<String> get() = renewableQuery(renewableQuery)

    val native: Boolean get() = type == "native"

    /** Seconds between imports. */
    val refreshInterval: Long get() = refreshSeconds ?: if (native) 3600 else 900

    /** The XMLTV this provider's implicit guide reads, configured or the service's published one. */
    val effectiveGuideUrl: String? get() = guideUrl ?: service?.let { NativeServices.defaultGuideUrl(it, options) }

    fun validate() {
        require(id.matches(idPattern)) { "Invalid provider ID" }
        require(type in setOf("m3u", "native")) { "Provider type must be m3u or native" }
        if (native) {
            require(service != null && service in NativeServices.names) {
                "Unknown native service; known: ${NativeServices.names.sorted()}"
            }
            require(playlistUrl == null) { "A native provider takes no playlistUrl" }
            require(
                options.all { (key, value) ->
                    key.matches(optionKey) && value.length <= 256
                },
            ) { "Invalid provider options" }
        } else {
            require(service == null && options.isEmpty()) { "service and options apply to native providers" }
            httpUri(requireNotNull(playlistUrl) { "A playlistUrl is required" })
        }
        guideUrl?.let(::httpUri)
        require(defaultProtocol == null || defaultProtocol in setOf("hls", "mpegts")) { "Unsupported default protocol" }
        require(refreshInterval >= 60) { "Refresh interval must be at least 60 seconds" }
        validateHeaders(headers)
        renewableQuery?.let(::validateRenewableQuery)
    }
}

/** One XMLTV guide source, plain or gzip, or (with [provider]) the guide a native provider's service publishes through its API. */
@Serializable data class GuideConfig(
    val id: String,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val refreshSeconds: Long = 21_600,
    val provider: String? = null,
) {
    fun validate() {
        require(id.matches(idPattern)) { "Invalid guide ID" }
        require((url == null) != (provider == null)) { "A guide needs a url" }
        url?.let(::httpUri)
        require(refreshSeconds >= 300) { "Guide refresh interval must be at least 300 seconds" }
        validateHeaders(headers)
    }
}

data class SourceConfig(val providers: List<FeedConfig> = emptyList(), val guides: List<GuideConfig> = emptyList()) {
    /** Explicit guides first, then one implicit guide per provider: its `guideUrl`, the service's published XMLTV, or the service's own guide. */
    val allGuides: List<GuideConfig> get() = guides + providers.mapNotNull { provider ->
        val url = provider.effectiveGuideUrl
        when {
            url != null -> GuideConfig(provider.id, url, provider.headers, provider.refreshInterval.coerceAtLeast(300))

            provider.native && NativeServices.hasGuide(
                provider.service!!,
            ) -> GuideConfig(provider.id, refreshSeconds = NATIVE_GUIDE_REFRESH, provider = provider.id)

            else -> null
        }
    }
}

/** Native guides cover hours to days ahead and some are fetched per channel; an hour keeps every horizon filled. */
const val NATIVE_GUIDE_REFRESH: Long = 3600

/** The source file as written: providers and guides, with `${NAME}` references left as they are. */
@Serializable data class SourceDocument(val providers: List<FeedConfig> = emptyList(), val guides: List<GuideConfig> = emptyList())

private val configJson = Json { ignoreUnknownKeys = false }

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val writtenSourceJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Reads the source file: a JSON array of providers, or an object with `providers` and `guides`.
 * `${NAME}` inside any string is replaced from [environment], so tokens stay out of the file.
 */
fun loadSources(path: Path?, environment: Map<String, String> = System.getenv()): SourceConfig {
    if (path == null) return SourceConfig()
    return validateSources(readSourceDocument(path), environment)
}

/** The source file as written, without variable expansion, so a write keeps `${NAME}` references. */
fun readSourceDocument(path: Path): SourceDocument {
    require(Files.size(path) <= 1_048_576) { "Source configuration is too large" }
    return when (val element = configJson.parseToJsonElement(Files.readString(path))) {
        is JsonArray -> SourceDocument(providers = configJson.decodeFromJsonElement(element))
        is JsonObject -> configJson.decodeFromJsonElement<SourceDocument>(element)
        else -> throw IllegalArgumentException("Source configuration must be a JSON array or object")
    }
}

/** [document] with `${NAME}` references replaced from [environment], checked the way the server checks the file at start. */
fun validateSources(document: SourceDocument, environment: Map<String, String> = System.getenv()): SourceConfig {
    val expanded = configJson.decodeFromJsonElement<SourceDocument>(
        expandVariables(configJson.encodeToJsonElement(document), environment),
    )
    val config = SourceConfig(expanded.providers, expanded.guides)
    require(config.providers.map { it.id }.distinct().size == config.providers.size) { "Duplicate provider IDs" }
    config.providers.forEach { it.validate() }
    config.guides.forEach { it.validate() }
    require(
        config.allGuides.map {
            it.id
        }.distinct().size == config.allGuides.size,
    ) { "Duplicate guide IDs (a provider guideUrl uses the provider ID)" }
    return config
}

/** [document] as the server writes a source file: an object with `providers` and `guides`, two-space indent, defaults left out. */
fun sourceBytes(document: SourceDocument): ByteArray = (
    writtenSourceJson.encodeToString(SourceDocument.serializer(), document) + "\n"
    ).toByteArray()

private val variable = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")

internal fun expandVariables(element: JsonElement, environment: Map<String, String>): JsonElement = when (element) {
    is JsonPrimitive -> if (element.isString) {
        JsonPrimitive(
            variable.replace(element.content) { match ->
                environment[match.groupValues[1]]
                    ?: throw IllegalArgumentException(
                        "Source configuration references unset variable ${match.groupValues[1]}",
                    )
            },
        )
    } else {
        element
    }

    is JsonArray -> JsonArray(element.map { expandVariables(it, environment) })

    is JsonObject -> JsonObject(element.mapValues { expandVariables(it.value, environment) })
}

fun httpUri(value: String): URI = URI(value).also {
    require(it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null) {
        "Expected an HTTP(S) URL without user info"
    }
}

@Serializable data class Offering(
    val id: String,
    val channelId: String,
    val providerId: String,
    val url: String,
    val protocol: String,
)

@Serializable data class FeedSnapshot(
    val providerId: String,
    val channels: List<Channel>,
    val offerings: List<Offering>,
    val importedAt: String,
)
fun stableId(value: String): String = java.util.UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

/** An XMLTV `<channel>`, or a channel id seen only on programmes (then [displayName] is the id). */
data class GuideChannel(val id: String, val displayName: String, val icon: String? = null)

/** One XMLTV `<episode-num>`: its `system` (`xmltv_ns`, `onscreen`, `dd_progid` and so on) and its value as the source wrote it. */
@Serializable data class EpisodeNumber(val system: String, val value: String)

/** A content rating such as `TV-14`, with the rating system (`VCHIP`, `MPAA`) when the source names one. */
@Serializable data class ProgrammeRating(val value: String, val system: String? = null)

/** An XMLTV `<previously-shown>`: the airing is a repeat, first shown at [start] (XMLTV time text) when known. */
@Serializable data class PreviouslyShown(val start: String? = null)

/**
 * A guide programme and the metadata its source provides. [date] is the XMLTV original date, `YYYY`, `YYYYMM` or
 * `YYYYMMDD`; [language] is the language code of the title. [bounded] applies the storage caps.
 */
data class GuideProgramme(
    val channelId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val title: String,
    val description: String? = null,
    val subTitle: String? = null,
    val episodeNumbers: List<EpisodeNumber> = emptyList(),
    val icon: String? = null,
    val categories: List<String> = emptyList(),
    val rating: ProgrammeRating? = null,
    val isNew: Boolean = false,
    val previouslyShown: PreviouslyShown? = null,
    val date: String? = null,
    val language: String? = null,
) {
    /** This programme with every metadata field cut to what storage keeps; a field that fails its check is dropped. */
    fun bounded(): GuideProgramme = copy(
        title = title.take(TITLE_LIMIT),
        description = description?.take(DESCRIPTION_LIMIT),
        subTitle = subTitle?.trim()?.take(TITLE_LIMIT)?.takeIf { it.isNotEmpty() && it != title },
        episodeNumbers = episodeNumbers.mapNotNull { number ->
            val system = number.system.trim().takeIf { it.matches(SYSTEM) } ?: return@mapNotNull null
            val value =
                number.value.trim().takeIf { it.isNotEmpty() && it.length <= EPISODE_VALUE_LIMIT }
                    ?: return@mapNotNull null
            EpisodeNumber(system, value)
        }.distinct().take(EPISODE_NUMBER_LIMIT),
        icon = normalizeLogoUrl(icon),
        categories = categories.map { it.trim().take(CATEGORY_LENGTH_LIMIT) }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.take(CATEGORY_LIMIT),
        rating = rating?.let { r ->
            val value = r.value.trim().takeIf { it.isNotEmpty() && it.length <= RATING_LIMIT } ?: return@let null
            ProgrammeRating(value, r.system?.trim()?.takeIf { it.isNotEmpty() && it.length <= RATING_LIMIT })
        },
        previouslyShown = previouslyShown?.let {
            PreviouslyShown(it.start?.trim()?.takeIf { start -> start.matches(XMLTV_TIME) })
        },
        date = date?.let(::xmltvDate),
        language = language?.trim()?.takeIf { it.matches(LANGUAGE) },
    )

    companion object {
        const val TITLE_LIMIT = 4096
        const val DESCRIPTION_LIMIT = 16384
        const val EPISODE_NUMBER_LIMIT = 8
        const val EPISODE_VALUE_LIMIT = 64
        const val CATEGORY_LIMIT = 8
        const val CATEGORY_LENGTH_LIMIT = 128
        const val RATING_LIMIT = 32
        private val SYSTEM = Regex("[A-Za-z0-9_.:-]{1,32}")
        private val LANGUAGE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8}){0,2}")
        private val XMLTV_TIME = Regex("\\d{4,14}( ?[+-]\\d{4})?")

        /** The first four, six or eight digits of an XMLTV date (`2024`, `20240115`, `2024-01-15`), or null. */
        fun xmltvDate(raw: String): String? {
            val digits = raw.trim().takeWhile { it.isDigit() || it == '-' }.filter(Char::isDigit)
            val date = when {
                digits.length >= 8 -> digits.take(8)
                digits.length == 6 || digits.length == 4 -> digits
                else -> return null
            }
            return date.takeIf { it.take(4).toInt() in 1800..2999 }
        }
    }
}

/**
 * A household channel: the app sees these, in this order, and nothing else. [offeringIds] is the failover order.
 * [autoDisabledAt] is set while a lineup policy, not the household, holds the entry disabled.
 */
data class LineupEntryRecord(
    val id: String,
    val number: String,
    val name: String,
    val group: String? = null,
    val logo: String? = null,
    val enabled: Boolean = true,
    val guide: GuideMapping? = null,
    val offeringIds: List<String> = emptyList(),
    val createdBy: String = "manual",
    val autoDisabledAt: Instant? = null,
    val autoDisabledReason: String? = null,
)
