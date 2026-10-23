package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl

/** What the Settings screen accepts as a Sofa King server address and API token before it saves them. */
object ServerAddress {
    /** A DNS name, an IPv4 address or a bracketed IPv6 literal, as the URL grammar allows in a host. */
    private val hostPattern = Regex("^(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)$")
    private const val ILLEGAL = " \"<>\\^`{|}"

    /** The port the server's Compose file publishes, added to an address typed without a scheme or a port. */
    const val DEFAULT_PORT = 9193

    /**
     * [raw] trimmed and without a trailing slash, or null unless it is an absolute http or https URL with a
     * host and nothing else, which is what the server's `/api/v1` and `/media` paths are appended to. A bare
     * address, `192.168.1.10` or `sofa.lan:8080` as the admin portal shows it, gets `http://`, and [DEFAULT_PORT]
     * when it has no port.
     */
    fun normalizeUrl(raw: String): String? {
        val typed = raw.trim().trimEnd('/')
        if (typed.isEmpty()) return null
        // Read before the slashes are trimmed, so `http://` alone stays a scheme without a host.
        val bare = "://" !in raw
        // An IPv6 literal needs its brackets before a port can follow it.
        if (bare && !typed.startsWith('[') && typed.count { it == ':' } > 1) return null
        val trimmed = if (!bare) typed else "http://$typed" + if (hasPort(typed)) "" else ":$DEFAULT_PORT"
        if (trimmed.any { it.isISOControl() || it in ILLEGAL }) return null
        // Nothing after the host and port: the trailing slash is gone, so any slash left is a path (or an empty host).
        if ('/' in trimmed.substringAfter("://", "")) return null
        // Ktor's parser, which the API client uses, decides what is a URL; an empty port ("host:") is allowed, as
        // `java.net.URI` allowed it, so it is parsed without the colon.
        val url = parseUrl(trimmed.removeSuffix(":").takeIf { trimmed.endsWith(':') } ?: trimmed) ?: return null
        if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return null
        if (!hostPattern.matches(url.host)) return null
        if (url.user != null || url.password != null) return null
        val parsedExtra = url.encodedQuery.isNotEmpty() || url.encodedFragment.isNotEmpty()
        if (parsedExtra || '?' in trimmed || '#' in trimmed) return null
        if (url.specifiedPort !in 0..65535) return null
        return trimmed
    }

    /** Whether a bare address names a port: after the bracket of an IPv6 literal, or after any colon otherwise. */
    private fun hasPort(bare: String): Boolean = if (bare.startsWith('[')) "]:" in bare else ':' in bare

    /**
     * [raw] trimmed, or null when it could not travel in an `Authorization` header: a token is printable ASCII
     * without spaces. Empty is valid; a server without `SOFA_API_TOKEN` takes requests without one.
     */
    fun normalizeToken(raw: String): String? = raw.trim().takeIf { token -> token.all { it in '!'..'~' } }

    /** [raw] as the six digits of a pairing code, spaces and dashes dropped, or null when it is not one. */
    fun normalizeCode(raw: String): String? = raw.filterNot { it == ' ' || it == '-' }.takeIf { code ->
        code.length == Wire.PAIRING_CODE_LENGTH && code.all { it in '0'..'9' }
    }
}
