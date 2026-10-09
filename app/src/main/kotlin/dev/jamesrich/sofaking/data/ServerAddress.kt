package dev.jamesrich.sofaking.data

import java.net.URI
import java.net.URISyntaxException

/** What the Settings screen accepts as a Dispatcharr address and profile before it saves them. */
object ServerAddress {
    /**
     * [raw] trimmed and without a trailing slash, or null unless it is an absolute http or https URL with a
     * host, which is what Dispatcharr's endpoints are appended to.
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
        if (uri.host.isNullOrEmpty()) return null
        return trimmed
    }

    /** [raw] trimmed, or null when nothing is left; the profile is a path segment on the server. */
    fun normalizeProfile(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && !it.contains('/') }
}
