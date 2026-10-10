package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import java.net.URI
import kotlin.coroutines.coroutineContext

class MediaRelay(
    private val http: HttpClient,
    private val feeds: FeedService,
    private val sessions: SessionManager,
    scope: CoroutineScope,
    fanout: FanoutConfig = FanoutConfig(),
) {
    private val permits = Semaphore(32)
    internal val shared = SharedStreams(scope, fanout)

    suspend fun serve(
        call: ApplicationCall,
        id: String,
        epoch: Int,
        token: String,
    ) = coroutineScope {
        val job = coroutineContext[Job]!!
        val resource = sessions.acquire(id, epoch, token, job)
        try {
            if (resource.shared) serveShared(call, resource) else serveDirect(call, id, epoch, resource)
        } finally {
            withContext(NonCancellable) { sessions.finished(id, job) }
        }
    }

    private suspend fun serveShared(
        call: ApplicationCall,
        resource: MediaResource,
    ) {
        val headers = feeds.config(resource.offering.providerId).headers
        val reader =
            shared.join(resource.offering.id) { consume ->
                permits.withPermit {
                    http.withUpstreamResponse(resource.url, resource.offering.url, headers, unbounded = true) { response ->
                        if (response.status.value != 200) throw UpstreamStatusException(response.status.value)
                        consume(response.bodyAsChannel())
                    }
                }
            }
        try {
            try {
                reader.awaitStarted()
            } catch (_: Exception) {
                throw ApiFailure(502, "UPSTREAM_UNAVAILABLE", "Upstream media unavailable", true)
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytesWriter(contentType = ContentType.parse("video/mp2t")) {
                for (chunk in reader.chunks) {
                    writeFully(chunk)
                    flush()
                }
            }
        } finally {
            withContext(NonCancellable) { reader.close() }
        }
    }

    private suspend fun serveDirect(
        call: ApplicationCall,
        id: String,
        epoch: Int,
        resource: MediaResource,
    ) {
        permits.withPermit {
            http.withUpstreamResponse(
                resource.url,
                resource.offering.url,
                feeds.config(resource.offering.providerId).headers,
                range = call.request.headers[HttpHeaders.Range],
                unbounded = true,
            ) { response ->
                if (response.status.value !in setOf(200, 206)) {
                    throw ApiFailure(502, "UPSTREAM_UNAVAILABLE", "Upstream media unavailable", true)
                }
                val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';')
                val manifest = URI(response.request.url.toString()).path.endsWith(".m3u8", true) || type.contains("mpegurl", true)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                if (manifest) {
                    val bytes = response.bodyAsChannel().readBuffer(2_097_153L).readByteArray()
                    if (bytes.size > 2_097_152) throw ApiFailure(502, "INVALID_MANIFEST", "Manifest too large")
                    val rewritten =
                        HlsRewriter.rewrite(
                            bytes.toString(Charsets.UTF_8),
                            URI(response.request.url.toString()),
                        ) { url -> sessions.resourcePath(id, epoch, url) }
                    call.respondText(rewritten, ContentType.parse("application/vnd.apple.mpegurl"))
                } else {
                    response.headers[HttpHeaders.ContentRange]?.let { call.response.header(HttpHeaders.ContentRange, it) }
                    response.headers[HttpHeaders.AcceptRanges]?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
                    val contentType =
                        try {
                            ContentType.parse(type.ifBlank { "video/mp2t" })
                        } catch (_: Exception) {
                            ContentType.Application.OctetStream
                        }
                    call.respondBytesWriter(contentType = contentType, status = response.status) {
                        response.bodyAsChannel().copyTo(this)
                    }
                }
            }
        }
    }
}
