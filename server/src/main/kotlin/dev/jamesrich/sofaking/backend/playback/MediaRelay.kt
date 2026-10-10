package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

class MediaRelay(private val http: HttpClient, private val feeds: FeedService, private val sessions: SessionManager) {
    private val permits = Semaphore(32)
    suspend fun serve(call: ApplicationCall, id: String, epoch: Int, token: String) = coroutineScope {
        val job = coroutineContext[Job]!!
        val resource = sessions.acquire(id, epoch, token, job)
        try {
            permits.withPermit {
                http.prepareGet(resource.url) {
                    feeds.config(resource.offering.providerId).headers.forEach { (key, value) -> header(key, value) }
                    call.request.headers[HttpHeaders.Range]?.let { header(HttpHeaders.Range, it) }
                    // TS streams are unbounded; inactivity remains bounded.
                    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = 15_000 }
                }.execute { response ->
                    if (response.status.value !in setOf(200, 206)) throw ApiFailure(502, "UPSTREAM_UNAVAILABLE", "Upstream media unavailable", true)
                    val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';')
                    val manifest = URI(response.request.url.toString()).path.endsWith(".m3u8", true) || type.contains("mpegurl", true)
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    if (manifest) {
                        val bytes = response.bodyAsChannel().readBuffer(2_097_153L).readByteArray()
                        if (bytes.size > 2_097_152) throw ApiFailure(502, "INVALID_MANIFEST", "Manifest too large")
                        val rewritten = HlsRewriter.rewrite(bytes.toString(Charsets.UTF_8), URI(response.request.url.toString())) { url -> sessions.resourcePath(id, epoch, url) }
                        call.respondText(rewritten, ContentType.parse("application/vnd.apple.mpegurl"))
                    } else {
                        response.headers[HttpHeaders.ContentRange]?.let { call.response.header(HttpHeaders.ContentRange, it) }
                        response.headers[HttpHeaders.AcceptRanges]?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
                        val contentType = try { ContentType.parse(type.ifBlank { "video/mp2t" }) } catch (_: Exception) { ContentType.Application.OctetStream }
                        call.respondBytesWriter(contentType = contentType, status = response.status) {
                            response.bodyAsChannel().copyTo(this)
                        }
                    }
                }
            }
        } finally { withContext(NonCancellable) { sessions.finished(id, job) } }
    }
}
