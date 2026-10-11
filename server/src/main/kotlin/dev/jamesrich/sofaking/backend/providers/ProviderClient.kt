package dev.jamesrich.sofaking.backend.providers

import dev.jamesrich.sofaking.backend.catalog.ParsedGuide
import java.net.URLEncoder
import java.time.Instant

/**
 * A channel as its service lists it. [id] is the service's own stable identifier and doubles as the
 * channel id in the service's guide, so a lineup entry made from it maps its guide automatically.
 */
data class ProviderChannel(
    val id: String,
    val name: String,
    val number: String? = null,
    val logo: String? = null,
    val group: String? = null,
    /** Region or other tags after the group, for example `us`. */
    val tags: List<String> = emptyList(),
)

/** A URL the relay can fetch, the headers every request to it needs, and when it stops working (null: it does not expire). */
data class ResolvedStream(val url: String, val headers: Map<String, String> = emptyMap(), val expiresAt: Instant? = null)

/**
 * A provider call that failed. [transient] means the service or the network is at fault (provider evidence);
 * otherwise the channel is gone from the service (offering evidence). Messages never carry tokens or URLs.
 */
class ProviderFailure(message: String, val transient: Boolean = true, cause: Throwable? = null) : RuntimeException(message, cause)

/** One service's unofficial client: the channel list, its own guide when it has one, and a playable URL per channel. */
interface ProviderClient {
    val service: String

    suspend fun channels(): List<ProviderChannel>

    /** The service's guide: every channel in the index, programmes only for the ids in [wanted]. Null when the service has none. */
    suspend fun guide(wanted: Set<String>): ParsedGuide? = null

    suspend fun resolve(channelId: String): ResolvedStream
}

/** Which services exist and whether each carries a guide of its own, known before any client is built. */
object NativeServices {
    private val guides = mapOf(
        "whale" to true, "vizio" to true, "lg" to true, "freelivesports" to true,
    )

    val names: Set<String> get() = guides.keys

    fun hasGuide(service: String): Boolean = guides[service] == true

    /** An XMLTV guide published for the service, used when the service has no guide API of its own. */
    fun defaultGuideUrl(service: String, options: Map<String, String>): String? = when (service) {
        "samsung" -> "https://i.mjh.nz/SamsungTVPlus/${options["region"] ?: "us"}.xml.gz"
        else -> null
    }

    /** The URL a native offering carries: the service and its channel id, nothing a player could fetch. */
    fun offeringUrl(service: String, channelId: String): String = "svc://$service/${URLEncoder.encode(channelId, Charsets.UTF_8)}"

    fun isNative(url: String): Boolean = url.startsWith("svc://")

    /** The channel id a native offering URL names. */
    fun channelId(url: String): String = java.net.URI(url).path.removePrefix("/")
}

/**
 * Ad-insertion templates: `[name]`, `[%name%]`, `{NAME}` and the percent-encoded `%7BNAME%7D`. Known names
 * take their URL-encoded value, unknown ones become empty, and nothing in either bracket form survives.
 */
object Macros {
    private val bracket = Regex("""\[%?([A-Za-z0-9_-]+)%?]""")
    private val brace = Regex("""(?:\{|%7[bB])([A-Za-z0-9_-]+)(?:}|%7[dD])""")

    fun fill(template: String, values: Map<String, String>): String {
        val byName = values.mapKeys { it.key.lowercase() }
        fun value(name: String) = byName[name.lowercase()]?.let { URLEncoder.encode(it, Charsets.UTF_8) } ?: ""
        return brace.replace(bracket.replace(template) { value(it.groupValues[1]) }) { value(it.groupValues[1]) }
    }
}
