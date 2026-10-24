package dev.jamesrich.sofaking.backend.upstream

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import java.net.InetAddress
import java.net.URI

/** An unbounded download still fails once the socket stays silent this long. */
private const val UNBOUNDED_SOCKET_TIMEOUT_MS = 15_000L

/** An upstream address or redirect the relay refuses to follow; answered as 502, never 500. */
class UpstreamRejectedException(message: String) : RuntimeException(message)

/**
 * Which upstream hosts the server will fetch. RFC1918 LAN hosts are always allowed because the
 * household's sources live there; loopback, link-local and unspecified literals are refused unless
 * [allowLoopback] is set for a development fixture. Hostnames are not resolved before the request.
 */
data class UpstreamPolicy(val allowLoopback: Boolean = false) {
    fun check(target: URI) {
        rejection(target)?.let { throw UpstreamRejectedException(it) }
    }

    /** Why the relay refuses to fetch [target], or null when it may. */
    private fun rejection(target: URI): String? {
        val host = literalHost(target) ?: return UNSUPPORTED
        val literal = if (host.all { it.isDigit() || it == '.' } || host.contains(':')) {
            runCatching { InetAddress.getByName(host) }.getOrNull()
        } else {
            null
        }
        val loopback = literal?.isLoopbackAddress ?: (host == "localhost" || host.endsWith(".localhost"))
        return when {
            literal != null && literal.unroutable -> "Unroutable upstream address"
            loopback && !allowLoopback -> "Loopback upstream address"
            else -> null
        }
    }
}

/** [target]'s host, lowercase and without brackets or a trailing dot; null for one the relay never fetches. */
private fun literalHost(target: URI): String? {
    val host = target.host?.takeIf { target.scheme in UPSTREAM_SCHEMES && target.userInfo == null }
        ?.trim('[', ']')?.lowercase()?.removeSuffix(".")
        ?: return null
    // The C resolver FFmpeg uses reads 0x7f000001, 0177.0.0.1 and 2130706433 as 127.0.0.1; only a plain
    // dotted quad is a literal here.
    val numeric = !host.contains(':') && host.split('.').all { NUMERIC_PART.matches(it) }
    return host.takeUnless { numeric && !DOTTED_QUAD.matches(it) }
}

private val InetAddress.unroutable: Boolean get() = isLinkLocalAddress || isAnyLocalAddress || isMulticastAddress

private const val UNSUPPORTED = "Unsupported upstream address"
private val UPSTREAM_SCHEMES = setOf("http", "https")
private val NUMERIC_PART = Regex("0x[0-9a-f]*|[0-9]+")
private val DOTTED_QUAD =
    Regex("((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])")

/** The client must disable automatic redirects. Credentials are scoped to the configured origin. */
internal suspend fun <T> HttpClient.withUpstreamResponse(
    url: String,
    credentialOrigin: String,
    providerHeaders: Map<String, String>,
    range: String? = null,
    unbounded: Boolean = false,
    requestTimeoutMillis: Long? = null,
    policy: UpstreamPolicy = UpstreamPolicy(),
    consume: suspend (HttpResponse) -> T,
): T {
    val start = runCatching { URI(url) }.getOrNull() ?: throw UpstreamRejectedException("Invalid upstream address")
    val origin = URI(credentialOrigin)
    // Every hop after the first stays on https once it is there, as followRedirects requires.
    if (origin.scheme == "https" &&
        start.scheme != "https"
    ) {
        throw UpstreamRejectedException(RedirectProblem.INSECURE.message)
    }
    return followRedirects(start, reject = { throw UpstreamRejectedException(it.message) }) { target ->
        policy.check(target)
        prepareGet(target.toString()) {
            providerHeaders.forEach { (key, value) ->
                if (sameOrigin(origin, target) || key.lowercase() in portableHeaders) header(key, value)
            }
            range?.let { header(HttpHeaders.Range, it) }
            if (unbounded) {
                timeout {
                    this.requestTimeoutMillis = Long.MAX_VALUE
                    socketTimeoutMillis = UNBOUNDED_SOCKET_TIMEOUT_MS
                }
            } else if (requestTimeoutMillis != null) {
                timeout {
                    this.requestTimeoutMillis = requestTimeoutMillis
                    socketTimeoutMillis = requestTimeoutMillis
                }
            }
        }.execute { response ->
            if (response.status.value in REDIRECT_STATUSES) {
                val location =
                    response.headers[HttpHeaders.Location]
                        ?: throw UpstreamRejectedException("Missing redirect location")
                response.bodyAsChannel().cancel(null)
                Hop.Redirect(location)
            } else {
                Hop.Done(consume(response))
            }
        }
    }
}

internal val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/** What one request answered: the caller's result, or a redirect to follow. */
internal sealed interface Hop<out T> {
    class Done<T>(val value: T) : Hop<T>

    class Redirect(val location: String) : Hop<Nothing>
}

internal enum class RedirectProblem(val message: String) {
    TOO_MANY("Too many upstream redirects"),
    INVALID("Invalid redirect location"),
    INSECURE("Insecure upstream redirect"),
}

/**
 * Redirects followed by hand, for a client that never follows them itself. [send] makes one request and has closed
 * its response by the time it returns, so a hop never stays open behind the next. At most [max] redirects; an
 * unparseable location or a step down from https goes to [reject].
 */
internal suspend fun <T> followRedirects(
    start: URI,
    max: Int = 5,
    reject: (RedirectProblem) -> Nothing,
    send: suspend (URI) -> Hop<T>,
): T {
    var target = start
    var redirects = 0
    while (true) {
        when (val hop = send(target)) {
            is Hop.Done -> return hop.value

            is Hop.Redirect -> {
                if (++redirects > max) reject(RedirectProblem.TOO_MANY)
                val next = try {
                    target.resolve(hop.location)
                } catch (_: IllegalArgumentException) {
                    reject(RedirectProblem.INVALID)
                }
                if (target.scheme == "https" && next.scheme != "https") reject(RedirectProblem.INSECURE)
                target = rawGitHub(next)
            }
        }
    }
}

/**
 * A github.com `/{owner}/{repo}/raw/{ref}/{path}` address as the raw.githubusercontent.com one it redirects to.
 * github.com's own answer carries a Content-Security-Policy line longer than the 8 KB the client reads, so the
 * request fails before the redirect is seen; mirrors such as i.mjh.nz send their files there.
 */
internal fun rawGitHub(uri: URI): URI {
    if (uri.scheme != "https" || !uri.host.equals("github.com", ignoreCase = true) || uri.rawQuery != null) return uri
    val (owner, repo, file) = GITHUB_RAW_PATH.matchEntire(uri.rawPath)?.destructured ?: return uri
    return URI("https://raw.githubusercontent.com/$owner/$repo/$file")
}

/** `/{owner}/{repo}/raw/{ref}/{path}`. */
private val GITHUB_RAW_PATH = Regex("/([^/]*)/([^/]*)/raw/([^/]*/.*)")

private val portableHeaders = setOf("user-agent", "accept", "accept-language", "origin", "referer")

internal fun sameOrigin(left: URI, right: URI): Boolean {
    fun port(uri: URI) = if (uri.port != -1) {
        uri.port
    } else if (uri.scheme == "https") {
        URLProtocol.HTTPS.defaultPort
    } else {
        URLProtocol.HTTP.defaultPort
    }
    return left.scheme.equals(right.scheme, true) && left.host.equals(right.host, true) && port(left) == port(right)
}
