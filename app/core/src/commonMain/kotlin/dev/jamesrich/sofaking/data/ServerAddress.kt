package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl

/** What the Settings screen accepts as a Sofa King server address and API token before it saves them. */
object ServerAddress {
    /** A DNS name, an IPv4 address or a bracketed IPv6 literal, as the URL grammar allows in a host. */
    private val hostPattern = Regex("^(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)$")
    private const val ILLEGAL = " \"<>\\^`{|}"

    /**
     * [raw] trimmed and without a trailing slash, or null unless it is an absolute http or https URL with a
     * host and nothing else, which is what the server's `/api/v1` and `/media` paths are appended to.
     */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.any { it.isISOControl() || it in ILLEGAL }) return null
        // Nothing after the host and port: the trailing slash is gone, so any slash left is a path (or an empty host).
        if ('/' in trimmed.substringAfter("://", "")) return null
        // Ktor's parser, which the API client uses, decides what is a URL; an empty port ("host:") is allowed, as
        // `java.net.URI` allowed it, so it is parsed without the colon.
        val url = parseUrl(trimmed.removeSuffix(":").takeIf { trimmed.endsWith(':') } ?: trimmed) ?: return null
        if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return null
        if (!hostPattern.matches(url.host)) return null
        if (url.user != null || url.password != null) return null
        if (url.encodedQuery.isNotEmpty() || url.encodedFragment.isNotEmpty() || '?' in trimmed || '#' in trimmed) return null
        if (url.specifiedPort !in 0..65535) return null
        return trimmed
    }

    /**
     * [raw] trimmed, or null when it could not travel in an `Authorization` header: a token is printable ASCII
     * without spaces. Empty is valid; a server without `SOFA_API_TOKEN` takes requests without one.
     */
    fun normalizeToken(raw: String): String? = raw.trim().takeIf { token -> token.all { it in '!'..'~' } }

    /** [raw] as the six digits of a pairing code, spaces and dashes dropped, or null when it is not one. */
    fun normalizeCode(raw: String): String? =
        raw.filterNot { it == ' ' || it == '-' }.takeIf { code -> code.length == Wire.PAIRING_CODE_LENGTH && code.all { it in '0'..'9' } }
}
