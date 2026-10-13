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
        val host = target.host.trim('[', ']').lowercase()
        val loopbackName = host == "localhost" || host.endsWith(".localhost")
        val literal = if (host.all { it.isDigit() || it == '.' } || host.contains(':')) runCatching { InetAddress.getByName(host) }.getOrNull() else null
        if (literal != null) {
            if (literal.isLinkLocalAddress || literal.isAnyLocalAddress || literal.isMulticastAddress) throw UpstreamRejectedException("Unroutable upstream address")
            if (literal.isLoopbackAddress && !allowLoopback) throw UpstreamRejectedException("Loopback upstream address")
        } else if (loopbackName && !allowLoopback) {
            throw UpstreamRejectedException("Loopback upstream address")
        }
    }
}

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

    suspend fun follow(
        target: URI,
        redirects: Int,
    ): T {
        policy.check(target)
        if (origin.scheme == "https" && target.scheme != "https") throw UpstreamRejectedException("Insecure upstream redirect")
        return prepareGet(target.toString()) {
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
            if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                if (redirects >= 5) throw UpstreamRejectedException("Too many upstream redirects")
                val location = response.headers[HttpHeaders.Location] ?: throw UpstreamRejectedException("Missing redirect location")
                val next = try { target.resolve(location) } catch (_: IllegalArgumentException) { throw UpstreamRejectedException("Invalid redirect location") }
                if (target.scheme == "https" && next.scheme != "https") throw UpstreamRejectedException("Insecure upstream redirect")
                response.bodyAsChannel().cancel(null)
                follow(next, redirects + 1)
            } else {
                consume(response)
            }
        }
    }
    val start = try { URI(url) } catch (_: Exception) { throw UpstreamRejectedException("Invalid upstream address") }
    return follow(start, 0)
}

internal fun probeHeadersSafe(headers: Map<String, String>): Boolean = headers.keys.all { it.lowercase() in portableHeaders }

private val portableHeaders = setOf("user-agent", "accept", "accept-language", "origin", "referer")

private fun sameOrigin(
    left: URI,
    right: URI,
): Boolean {
    fun port(uri: URI) =
        if (uri.port != -1) {
            uri.port
        } else if (uri.scheme == "https") {
            443
        } else {
            80
        }
    return left.scheme.equals(right.scheme, true) && left.host.equals(right.host, true) && port(left) == port(right)
}
