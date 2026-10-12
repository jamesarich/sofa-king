package dev.jamesrich.sofaking.data

/** What the Settings screen accepts as a Sofa King server address and API token before it saves them. */
object ServerAddress {
    private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$")
    private val hostPattern = Regex("^(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)$")
    private const val ILLEGAL = " \"<>\\^`{|}"

    /**
     * [raw] trimmed and without a trailing slash, or null unless it is an absolute http or https URL with a
     * host, which is what the server's `/api/v1` and `/media` paths are appended to.
     */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        val match = schemePattern.matchEntire(trimmed) ?: return null
        val scheme = match.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = match.groupValues[2]
        if (rest.any { it.isISOControl() || it in ILLEGAL }) return null
        if ('?' in rest || '#' in rest) return null
        val authority = rest.substringBefore('/')
        if ('@' in authority) return null
        val (host, port) = splitHostPort(authority) ?: return null
        if (!hostPattern.matches(host)) return null
        // An empty port ("host:") is allowed, as `java.net.URI` allowed it; anything else must be a valid number.
        if (!port.isNullOrEmpty() && (!port.all { it.isDigit() } || port.length > 5 || port.toInt() > 65535)) return null
        return trimmed
    }

    /** Host and the text after its colon (null without one); null when the authority cannot be split. */
    private fun splitHostPort(authority: String): Pair<String, String?>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val after = authority.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) return null
            return authority.substring(0, close + 1) to after.removePrefix(":").takeIf { after.isNotEmpty() }
        }
        val colon = authority.lastIndexOf(':')
        return if (colon < 0) authority to null else authority.substring(0, colon) to authority.substring(colon + 1)
    }

    /**
     * [raw] trimmed, or null when it could not travel in an `Authorization` header: a token is printable ASCII
     * without spaces. Empty is valid; a server without `SOFA_API_TOKEN` takes requests without one.
     */
    fun normalizeToken(raw: String): String? = raw.trim().takeIf { token -> token.all { it in '!'..'~' } }
}
