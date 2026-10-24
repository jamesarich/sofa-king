package dev.jamesrich.sofaking.backend.health

import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.UpstreamRejectedException
import dev.jamesrich.sofaking.backend.upstream.inflateHead
import dev.jamesrich.sofaking.backend.upstream.readHead
import dev.jamesrich.sofaking.backend.upstream.sniffProtocol
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.URI

/**
 * Fast qualification tier: one short GET that must answer 2xx with an MPEG-TS or M3U head.
 * Only a configured restream proxy's 502 is offering evidence; other 5xx and network faults stay provider evidence.
 */
internal class HttpQualifier(
    private val http: HttpClient,
    private val restreamOrigins: Set<String>,
    private val timeoutMillis: Long = 8000,
    private val policy: UpstreamPolicy = UpstreamPolicy(),
) {
    /** A passing check carries the protocol the body was seen to be; a failing one carries the failure class. */
    data class Qualification(val failure: FailureClass?, val protocol: String? = null)

    suspend fun check(offering: Offering, headers: Map<String, String>): Qualification = try {
        withTimeout(timeoutMillis) {
            http.withUpstreamResponse(
                offering.url,
                offering.url,
                headers,
                requestTimeoutMillis = timeoutMillis,
                policy = policy,
            ) { response ->
                val status = response.status
                if (status.isSuccess()) {
                    val protocol = sniffProtocol(inflateHead(response.bodyAsChannel().readHead(HEAD_BYTES)))
                    if (protocol == null) Qualification(FailureClass.OFFERING) else Qualification(null, protocol)
                } else {
                    Qualification(classifyStatus(status, isRestreamOrigin(offering.url, restreamOrigins)))
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        Qualification(FailureClass.PROVIDER)
    } catch (e: CancellationException) {
        throw e
    } catch (_: UpstreamRejectedException) {
        Qualification(FailureClass.OFFERING)
    } catch (_: IllegalArgumentException) {
        Qualification(FailureClass.OFFERING)
    } catch (_: Exception) {
        Qualification(FailureClass.PROVIDER)
    }

    companion object {
        const val HEAD_BYTES = 2048

        fun classifyStatus(status: HttpStatusCode, restream: Boolean): FailureClass = when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> FailureClass.AUTH
            status == HttpStatusCode.TooManyRequests -> FailureClass.RATE_LIMIT
            status == HttpStatusCode.BadGateway && restream -> FailureClass.OFFERING
            status.value >= HttpStatusCode.InternalServerError.value -> FailureClass.PROVIDER
            else -> FailureClass.OFFERING
        }

        fun isRestreamOrigin(url: String, origins: Set<String>): Boolean {
            if (origins.isEmpty()) return false
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: return false
            val port = if (uri.port != -1) {
                uri.port
            } else if (uri.scheme.equals("https", true)) {
                443
            } else {
                80
            }
            return host in origins || "$host:$port" in origins
        }
    }
}
