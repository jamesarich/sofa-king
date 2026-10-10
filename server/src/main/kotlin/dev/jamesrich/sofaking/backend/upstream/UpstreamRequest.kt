package dev.jamesrich.sofaking.backend.upstream

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import java.net.URI

/** The client must disable automatic redirects. Credentials are scoped to the configured origin. */
internal suspend fun <T> HttpClient.withUpstreamResponse(
    url: String,
    credentialOrigin: String,
    providerHeaders: Map<String, String>,
    range: String? = null,
    unbounded: Boolean = false,
    requestTimeoutMillis: Long? = null,
    consume: suspend (HttpResponse) -> T,
): T {
    val origin = URI(credentialOrigin)

    suspend fun follow(
        target: URI,
        redirects: Int,
    ): T {
        require(target.scheme in setOf("http", "https") && target.host != null && target.userInfo == null) {
            "Unsupported upstream address"
        }
        require(origin.scheme != "https" || target.scheme == "https") { "Insecure upstream redirect" }
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
                require(redirects < 5) { "Too many upstream redirects" }
                val location = requireNotNull(response.headers[HttpHeaders.Location]) { "Missing redirect location" }
                val next = target.resolve(location)
                require(target.scheme != "https" || next.scheme == "https") { "Insecure upstream redirect" }
                response.bodyAsChannel().cancel(null)
                follow(next, redirects + 1)
            } else {
                consume(response)
            }
        }
    }
    return follow(URI(url), 0)
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
