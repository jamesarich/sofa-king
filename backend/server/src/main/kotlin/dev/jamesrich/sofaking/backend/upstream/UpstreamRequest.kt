package dev.jamesrich.sofaking.backend.upstream

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import java.net.InetAddress
import java.net.URI

/** An upstream address or redirect the relay refuses to follow; answered as 502, never 500. */
class UpstreamRejectedException(message: String) : RuntimeException(message)

/**
 * Which upstream hosts the server will fetch. RFC1918 LAN hosts are always allowed because the
 * household's sources live there; loopback, link-local and unspecified literals are refused unless
 * [allowLoopback] is set for a development fixture. Hostnames are not resolved before the request.
 */
data class UpstreamPolicy(val allowLoopback: Boolean = false) {
    fun check(target: URI) {
        if (target.scheme !in setOf("http", "https") || target.host == null || target.userInfo != null) {
            throw UpstreamRejectedException("Unsupported upstream address")
        }
        val host = target.host.trim('[', ']').lowercase().removeSuffix(".")
        // The C resolver FFmpeg uses reads 0x7f000001, 0177.0.0.1 and 2130706433 as 127.0.0.1; only a plain dotted quad is a literal here.
        if (!host.contains(':') && host.split('.').all { NUMERIC_PART.matches(it) } && !DOTTED_QUAD.matches(host)) {
            throw UpstreamRejectedException("Unsupported upstream address")
        }
        val loopbackName = host == "localhost" || host.endsWith(".localhost")
        val literal = if (host.all { it.isDigit() || it == '.' } || host.contains(':')) {
            runCatching { InetAddress.getByName(host) }.getOrNull()
        } else {
            null
        }
        if (literal != null) {
            if (literal.isLinkLocalAddress || literal.isAnyLocalAddress || literal.isMulticastAddress) {
                throw UpstreamRejectedException("Unroutable upstream address")
            }
            if (literal.isLoopbackAddress && !allowLoopback) {
                throw UpstreamRejectedException("Loopback upstream address")
            }
        } else if (loopbackName && !allowLoopback) {
            throw UpstreamRejectedException("Loopback upstream address")
        }
    }
}

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
    val origin = URI(credentialOrigin)
    val start = try {
        URI(url)
    } catch (_: Exception) {
        throw UpstreamRejectedException("Invalid upstream address")
    }
    return followRedirects(start, reject = { problem ->
        throw UpstreamRejectedException(
            when (problem) {
                RedirectProblem.TOO_MANY -> "Too many upstream redirects"
                RedirectProblem.INVALID -> "Invalid redirect location"
                RedirectProblem.INSECURE -> "Insecure upstream redirect"
            },
        )
    }) { target ->
        policy.check(target)
        if (origin.scheme == "https" && target.scheme != "https") {
            throw UpstreamRejectedException("Insecure upstream redirect")
        }
        prepareGet(target.toString()) {
            providerHeaders.forEach { (key, value) ->
                if (sameOrigin(origin, target) || key.lowercase() in portableHeaders) header(key, value)
            }
            range?.let { header(HttpHeaders.Range, it) }
            if (unbounded) {
                timeout {
                    this.requestTimeoutMillis = Long.MAX_VALUE
                    socketTimeoutMillis = 15_000
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

internal enum class RedirectProblem { TOO_MANY, INVALID, INSECURE }

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
                } catch (
                    _: IllegalArgumentException,
                ) {
                    reject(RedirectProblem.INVALID)
                }
                if (target.scheme == "https" && next.scheme != "https") reject(RedirectProblem.INSECURE)
                target = next
            }
        }
    }
}

private val portableHeaders = setOf("user-agent", "accept", "accept-language", "origin", "referer")

internal fun sameOrigin(left: URI, right: URI): Boolean {
    fun port(uri: URI) = if (uri.port != -1) {
        uri.port
    } else if (uri.scheme == "https") {
        443
    } else {
        80
    }
    return left.scheme.equals(right.scheme, true) && left.host.equals(right.host, true) && port(left) == port(right)
}
