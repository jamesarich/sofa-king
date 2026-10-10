package dev.jamesrich.sofaking.data

import java.net.URI
import java.net.URISyntaxException

/** What the Settings screen accepts as a Sofa King server address and API token before it saves them. */
object ServerAddress {
    /**
     * [raw] trimmed and without a trailing slash, or null unless it is an absolute http or https URL with a
     * host, which is what the server's `/api/v1` and `/media` paths are appended to.
     */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.port < -1 || uri.port > 65535) return null
        return trimmed
    }

    /**
     * [raw] trimmed, or null when it could not travel in an `Authorization` header: a token is printable ASCII
     * without spaces. Empty is valid; a server without `SOFA_API_TOKEN` takes requests without one.
     */
    fun normalizeToken(raw: String): String? = raw.trim().takeIf { token -> token.all { it in '!'..'~' } }
}
