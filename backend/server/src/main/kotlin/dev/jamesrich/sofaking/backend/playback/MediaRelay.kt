package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.inflateHead
import dev.jamesrich.sofaking.backend.upstream.isGzip
import dev.jamesrich.sofaking.backend.upstream.readHead
import dev.jamesrich.sofaking.backend.upstream.sniffProtocol
import dev.jamesrich.sofaking.backend.upstream.tsSyncOffset
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readAvailable
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

private val MEDIA_STATUSES = setOf(200, 206)

class MediaRelay(
    private val http: HttpClient,
    private val feeds: FeedService,
    private val sessions: SessionManager,
    scope: CoroutineScope,
    fanout: FanoutConfig = FanoutConfig(),
) {
    /** Upstream requests in flight across the server, players' and FFmpeg's through [LoopbackRelay] alike. */
    internal val permits = Semaphore(32)

    private companion object {
        val DIRECTIVE_VALUE = Regex("[A-Za-z0-9_,]{1,32}")
    }
    internal val shared = SharedStreams(scope, fanout)

    suspend fun serve(
        call: ApplicationCall,
        id: String,
        epoch: Int,
        token: String,
    ) = coroutineScope {
        val job = coroutineContext[Job]!!
        val resource = sessions.acquire(id, epoch, token, job, call.request.origin.remoteAddress)
        try {
            // A native source resolves to its playable URL now; the headers it needs go with every request to it.
            val stream = try {
                feeds.playable(resource.offering)
            } catch (_: ProviderFailure) {
                throw ApiFailure(502, "UPSTREAM_UNAVAILABLE", "Upstream media unavailable", true)
            }
            if (resource.shared) serveShared(call, id, epoch, job, resource, stream) else serveDirect(call, id, epoch, job, resource, stream)
        } finally {
            withContext(NonCancellable) { sessions.finished(id, job) }
        }
    }

    /** LL-HLS players append delivery directives to the playlist URL they were given; forward them to the real playlist. */
    private fun withDeliveryDirectives(
        url: String,
        call: ApplicationCall,
    ): String {
        val directives =
            listOf("_HLS_msn", "_HLS_part", "_HLS_skip").mapNotNull { name ->
                call.request.queryParameters[name]?.takeIf { it.matches(DIRECTIVE_VALUE) }?.let { "$name=$it" }
            }
        if (directives.isEmpty()) return url
        return url + (if (URI(url).rawQuery == null) "?" else "&") + directives.joinToString("&")
    }

    /** The descriptor promised one protocol and the body carries the other: bump the epoch so the player re-reads it. */
    private suspend fun protocolMismatch(
        id: String,
        epoch: Int,
        job: Job,
        protocol: String,
    ): Nothing {
        sessions.protocolObserved(id, epoch, protocol, job)
        throw ApiFailure(410, Wire.ErrorCode.STREAM_CHANGED, "Stream protocol changed; fetch the current session descriptor", true)
    }

    /**
     * A reader of [offering]'s one upstream MPEG-TS connection, opening it from [stream] when nobody else reads it;
     * players and recordings of the same source share it.
     */
    internal suspend fun joinShared(offering: Offering, stream: ResolvedStream): TsFanout.Reader =
        shared.join(offering.id) { consume ->
            permits.withPermit {
                http.withUpstreamResponse(stream.url, stream.url, stream.headers, unbounded = true, policy = feeds.upstream) { response ->
                    if (response.status.value != 200) throw UpstreamStatusException(response.status.value)
                    consume(response.bodyAsChannel())
                }
            }
        }

    private suspend fun serveShared(
        call: ApplicationCall,
        id: String,
        epoch: Int,
        job: Job,
        resource: MediaResource,
        stream: ResolvedStream,
    ) {
        val reader = joinShared(resource.offering, stream)
        try {
            try {
                reader.awaitStarted()
            } catch (e: UpstreamProtocolException) {
                protocolMismatch(id, epoch, job, e.protocol)
            } catch (_: Exception) {
                // The session let this request go before the stream started (released, retuned, or replaced by the
                // player's next request); that cancels the wait, and is not the upstream's failure.
                if (!job.isActive) throw withContext(NonCancellable) { sessions.letGo(id) }
                throw ApiFailure(502, "UPSTREAM_UNAVAILABLE", "Upstream media unavailable", true)
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytesWriter(contentType = ContentType.parse("video/mp2t")) {
                for (chunk in reader.chunks) {
                    writeFully(chunk)
                    resource.bytes.addAndGet(chunk.size.toLong())
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
        job: Job,
        resource: MediaResource,
        stream: ResolvedStream,
    ) {
        // The root token maps to the offering's own URL, which for a native source is the resolved one; children are absolute already.
        val root = resource.url == resource.offering.url
        val url = if (root) stream.url else resource.url
        var cues: SegmentCues? = null
        permits.withPermit {
            relayResource(
                http, feeds.upstream, call, withDeliveryDirectives(url, call), stream,
                path = { sessions.resourcePath(id, epoch, it) },
                sent = { resource.bytes.addAndGet(it) },
                manifest = { text, source -> resource.playlists.steady(text, source).let { resource.ads?.annotate(it, source) ?: it } },
                sniffed = { protocol -> if (root && protocol == "mpegts") protocolMismatch(id, epoch, job, "mpegts") },
                cues = resource.ads?.let { { found -> cues = found } },
            )
        }
        resource.ads?.served(url, cues)
    }
}

/** How much of a segment read for cues passes through at a time. */
private const val SEGMENT_READ = 64 * 1024

/** A gzip-encoded resource is buffered to be inflated; keys are bytes, anything near this is not a key. */
private const val ENCODED_LIMIT = 8 * 1024 * 1024

/**
 * Answers [call] with one upstream resource, for a player's session or an FFmpeg lease alike. [url] is fetched with
 * [stream]'s headers, scoped to its origin, under [policy], following redirects by hand and checking every hop; the
 * call's `Range` goes along. A manifest comes back rewritten so every URI it names maps through [path] to this
 * server, a gzip-encoded small resource inflated, and anything else streamed as the upstream sent it. [manifest] may
 * add to a manifest's text, given the URL it came from, before the rewrite. [sniffed] sees
 * the protocol of the body's head before anything is answered, [sent] each count of bytes answered, and [refused]
 * an upstream status other than 200 or 206. Given [cues], a transport stream segment is read for SCTE-35 cues as it
 * streams (see [SpliceReader]), and [cues] gets what it carried once all of it is answered.
 */
internal suspend fun relayResource(
    http: HttpClient,
    policy: UpstreamPolicy,
    call: ApplicationCall,
    url: String,
    stream: ResolvedStream,
    path: suspend (String) -> String,
    sent: (Long) -> Unit = {},
    refused: (Int) -> Nothing = { throw ApiFailure(502, "UPSTREAM_UNAVAILABLE", "Upstream media unavailable", true) },
    sniffed: suspend (String?) -> Unit = {},
    manifest: (String, URI) -> String = { text, _ -> text },
    cues: ((SegmentCues) -> Unit)? = null,
) {
    http.withUpstreamResponse(
        url,
        stream.url,
        stream.headers,
        range = call.request.headers[HttpHeaders.Range],
        unbounded = true,
        policy = policy,
    ) { response ->
        if (response.status.value !in MEDIA_STATUSES) refused(response.status.value)
        val body = response.bodyAsChannel()
        val head = body.readHead(2048)
        // Some origins gzip a manifest whatever the request asked for; the signature is in the inflated bytes.
        val protocol = sniffProtocol(inflateHead(head))
        sniffed(protocol)
        val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';')
        val manifest =
            protocol == "hls" ||
                (protocol == null && (URI(response.request.url.toString()).path.endsWith(".m3u8", true) || type.contains("mpegurl", true)))
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (manifest) {
            val raw = head + body.readBuffer(2_097_153L - head.size).readByteArray()
            if (raw.size > 2_097_152) throw ApiFailure(502, "INVALID_MANIFEST", "Manifest too large")
            val bytes = if (isGzip(raw)) inflateHead(raw, 2_097_152) else raw
            val source = URI(response.request.url.toString())
            val rewritten = HlsRewriter.rewrite(manifest(bytes.toString(Charsets.UTF_8), source), source, path)
            sent(rewritten.length.toLong())
            call.respondText(rewritten, ContentType.parse("application/vnd.apple.mpegurl"))
        } else {
            val contentType =
                try {
                    ContentType.parse(type.ifBlank { "video/mp2t" })
                } catch (_: Exception) {
                    ContentType.Application.OctetStream
                }
            // A CDN may gzip a key or a small resource on its own; the reader gets the bytes it would decrypt with.
            val encoded = response.headers[HttpHeaders.ContentEncoding]?.contains("gzip", true) == true || (isGzip(head) && !type.startsWith("video/", true))
            if (encoded) {
                val raw = head + body.readBuffer(ENCODED_LIMIT + 1L - head.size).readByteArray()
                if (raw.size > ENCODED_LIMIT) throw ApiFailure(502, "UPSTREAM_UNAVAILABLE", "Encoded resource too large", true)
                val plain = if (isGzip(raw)) inflateHead(raw, ENCODED_LIMIT * 8) else raw
                sent(plain.size.toLong())
                call.respondBytes(plain, contentType)
            } else {
                response.headers[HttpHeaders.ContentRange]?.let { call.response.header(HttpHeaders.ContentRange, it) }
                response.headers[HttpHeaders.AcceptRanges]?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
                val sync = if (cues != null && protocol == "mpegts") tsSyncOffset(head, 3) else null
                val reader = sync?.let { SpliceReader() }
                call.respondBytesWriter(contentType = contentType, status = response.status) {
                    writeFully(head)
                    sent(head.size.toLong())
                    if (reader == null) {
                        sent(body.copyTo(this))
                    } else {
                        reader.feed(head, sync!!)
                        val buffer = ByteArray(SEGMENT_READ)
                        while (true) {
                            val count = body.readAvailable(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            writeFully(buffer, 0, count)
                            reader.feed(buffer, 0, count)
                            sent(count.toLong())
                        }
                    }
                }
                reader?.let { cues?.invoke(it.finish()) }
            }
        }
    }
}
