package dev.jamesrich.sofaking.backend.upstream

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.URI

/** An unbounded download still fails once the socket stays silent this long. */
private const val UNBOUNDED_SOCKET_TIMEOUT_MS = 15_000L

/** An upstream address or redirect the relay refuses to follow; answered as 502, never 500. */
class UpstreamRejectedException(message: String) : RuntimeException(message)

/**
 * The loopback route where the server serves streams it makes itself, such as virtual channels: [prefix] is set when
 * its listener starts, and only URLs under it are admitted.
 */
class LocalOrigin {
    @Volatile var prefix: String? = null

    fun admits(target: URI): Boolean = prefix?.let { target.normalize().toString().startsWith(it) } == true
}

/** Looks up every address of [host]; throws [java.net.UnknownHostException] for a name that does not resolve. */
fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

private val SystemResolver = HostResolver { InetAddress.getAllByName(it).toList() }

/** How long [UpstreamPolicy.admit] waits for a name to resolve before it refuses the host. */
private const val LOOKUP_TIMEOUT_MS = 3_000L

/**
 * Which upstream hosts the server will fetch. RFC1918 LAN hosts are always allowed because the
 * household's sources live there; loopback, link-local and unspecified addresses are refused unless
 * [allowLoopback] is set for a development fixture, or the URL is on the server's own [local] route. [check] judges
 * literals and `localhost` names without a lookup; [admit] also resolves any other name through [resolver] and refuses
 * the host when any of its addresses is refused. A name that does not resolve is left to the connection to fail.
 */
data class UpstreamPolicy(
    val allowLoopback: Boolean = false,
    val local: LocalOrigin = LocalOrigin(),
    val resolver: HostResolver = SystemResolver,
) {
    fun check(target: URI) {
        rejection(target)?.let { throw UpstreamRejectedException(it) }
    }

    /** [check], then a bounded lookup off the calling thread for a host that is a name, once per request or hop. */
    suspend fun admit(target: URI) {
        check(target)
        val host = literalHost(target) ?: return
        if (isLiteral(host) || isLocalName(host)) return
        val addresses = lookup(host) ?: throw UpstreamRejectedException("Upstream address lookup timed out")
        addresses.firstNotNullOfOrNull { addressRejection(it, target) }?.let { throw UpstreamRejectedException(it) }
    }

    /** The addresses of [host]; empty when it does not resolve, null when the lookup outlasts the limit. */
    private suspend fun lookup(host: String): List<InetAddress>? {
        // Not a child of the caller: a blocked resolver thread cannot be canceled, and must not hold the caller.
        val lookup = LookupScope.async { runCatching { resolver.resolve(host) }.getOrElse { emptyList() } }
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookup.await() }
    }

    /** Why the relay refuses to fetch [target], or null when it may. */
    private fun rejection(target: URI): String? {
        val host = literalHost(target) ?: return UNSUPPORTED
        val literal = if (isLiteral(host)) runCatching { InetAddress.getByName(host) }.getOrNull() else null
        if (literal != null) return addressRejection(literal, target)
        return if (isLocalName(host) && !allowLoopback && !local.admits(target)) LOOPBACK else null
    }

    private fun addressRejection(address: InetAddress, target: URI): String? = when {
        address.unroutable -> "Unroutable upstream address"
        address.isLoopbackAddress && !allowLoopback && !local.admits(target) -> LOOPBACK
        else -> null
    }
}

/** Few threads, so a resolver that hangs during a DNS outage cannot take the shared IO pool with it. */
private val LookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(LOOKUP_THREADS))

private const val LOOKUP_THREADS = 4

private fun isLiteral(host: String): Boolean = host.all { it.isDigit() || it == '.' } || host.contains(':')

private fun isLocalName(host: String): Boolean = host == "localhost" || host.endsWith(".localhost")

private const val LOOPBACK = "Loopback upstream address"

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
        policy.admit(target)
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

/**
 * Headers every host of a stream gets, not only the source's own: none is a credential, and a CDN may answer by them.
 * `X-Forwarded-For` is a region's address (see `RegionOptions`): a stream's keys and segments come from other hosts,
 * which answer by the caller's region too.
 */
private val portableHeaders = setOf("user-agent", "accept", "accept-language", "origin", "referer", "x-forwarded-for")

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
