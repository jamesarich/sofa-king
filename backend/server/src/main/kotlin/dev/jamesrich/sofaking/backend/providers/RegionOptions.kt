package dev.jamesrich.sofaking.backend.providers

/**
 * The `region` and `forwardedFor` options of a service that picks its lineup by the caller's address. `region` is the
 * two-letter code a channel is tagged with; `forwardedFor` is an address in that country, sent as `X-Forwarded-For`
 * on every request the client makes and every request the relay makes for its streams, so the service answers with
 * that country's lineup and streams. A malformed address fails each call as the provider's fault rather than the
 * server's start.
 */
internal class RegionOptions(options: Map<String, String>) {
    val region: String = options["region"]?.trim()?.lowercase()?.takeIf { REGION.matches(it) } ?: DEFAULT_REGION

    private val forwardedFor: String? = options["forwardedFor"]?.trim()?.takeIf { it.isNotEmpty() }

    /** `X-Forwarded-For` when `forwardedFor` is set; a non-transient [ProviderFailure] when it is not an IP address. */
    val headers: Map<String, String>
        get() = when {
            forwardedFor == null -> emptyMap()
            IPV4.matches(forwardedFor) || IPV6.matches(forwardedFor) -> mapOf("X-Forwarded-For" to forwardedFor)
            else -> throw ProviderFailure("forwardedFor is not an IP address", transient = false)
        }

    /** The channel tags: the region code. */
    val tags: List<String> get() = listOf(region)

    companion object {
        const val DEFAULT_REGION = "us"
        private val REGION = Regex("[a-z]{2}")
        private val IPV4 = Regex("""((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)""")
        private val IPV6 = Regex("""[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*""")
    }
}
