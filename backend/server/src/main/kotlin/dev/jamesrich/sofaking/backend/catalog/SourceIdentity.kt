package dev.jamesrich.sofaking.backend.catalog

import java.net.URI

/** Query parameter names (lowercase) that providers rotate; they never take part in source identity. */
val DEFAULT_RENEWABLE_QUERY: Set<String> = setOf(
    "token", "access_token", "auth", "sig", "signature", "expires", "exp", "key", "hash", "hmac", "nonce",
)

private val queryName = Regex("[A-Za-z0-9_.-]{1,64}")

fun validateRenewableQuery(names: Collection<String>) {
    require(
        names.all {
            it.matches(queryName)
        },
    ) { "Renewable query parameter names must be 1 to 64 letters, digits, '_', '.' or '-'" }
}

fun renewableQuery(names: Collection<String>?): Set<String> =
    names?.mapTo(HashSet()) { it.lowercase() } ?: DEFAULT_RENEWABLE_QUERY

/**
 * `scheme://authority/path` with the path percent-decoded, followed by `?name=value&...` for the query
 * parameters not named in [renewable], sorted by name then value. Null when [url] is not an absolute URL.
 */
fun sourceKey(url: String, renewable: Set<String> = DEFAULT_RENEWABLE_QUERY): String? {
    val uri = parse(url) ?: return null
    val identity = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.filterNot { pair ->
        pair.substringBefore('=').lowercase() in renewable
    }?.sorted().orEmpty()
    return pathKey(uri) + if (identity.isEmpty()) "" else "?" + identity.joinToString("&")
}

/** `scheme://authority/path` with every query parameter ignored. */
fun pathKey(url: String): String? = parse(url)?.let(::pathKey)

private fun pathKey(uri: URI): String = "${uri.scheme.lowercase()}://${uri.authority.lowercase()}${uri.path.orEmpty()}"

private fun parse(url: String): URI? = try {
    URI(url).takeIf { it.scheme != null && it.authority != null }
} catch (_: Exception) {
    null
}
