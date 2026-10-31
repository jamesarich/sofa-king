package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.MIB
import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.FeedService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.providers.ProviderFailure
import dev.jamesrich.sofaking.backend.providers.ResolvedStream
import dev.jamesrich.sofaking.backend.upstream.UpstreamPolicy
import dev.jamesrich.sofaking.backend.upstream.UpstreamRejectedException
import dev.jamesrich.sofaking.backend.upstream.inflateHead
import dev.jamesrich.sofaking.backend.upstream.isGzip
import dev.jamesrich.sofaking.backend.upstream.readHead
import dev.jamesrich.sofaking.backend.upstream.sniffProtocol
import dev.jamesrich.sofaking.backend.upstream.tsSyncOffset
import dev.jamesrich.sofaking.backend.upstream.withUpstreamResponse
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

private val MEDIA_STATUSES = setOf(200, 206)

class MediaRelay(
    private val http: HttpClient,
    private val feeds: FeedService,
    private val sessions: SessionManager,
    private val scope: CoroutineScope,
    /** Reads the audio of a session's segments for its learned breaks; none are learned without it. */
    private val audio: SegmentAudio? = null,
    /**
     * The FFmpeg and the directory that package MPEG-TS sources as HLS for players that play HLS only; without them,
     * those players get the MPEG-TS stream.
     */
    packaging: Pair<String, Path>? = null,
    fanout: FanoutConfig = FanoutConfig(),
) {
    /** Upstream requests in flight across the server, players' and FFmpeg's through [LoopbackRelay] alike. */
    internal val permits = Semaphore(UPSTREAM_REQUESTS)

    private companion object {
        val DIRECTIVE_VALUE = Regex("[A-Za-z0-9_,]{1,32}")
    }
    internal val shared = SharedStreams(scope, fanout)

    private val captions = CaptionPlaylists(http, feeds.upstream)

    private val packages = packaging?.let { (ffmpeg, directory) ->
        PackagedStreams(ffmpeg, directory, scope, sessions.media, ::joinShared).also { sessions.packaging = true }
    }

    /** MPEG-TS sources being packaged as HLS now, each one FFmpeg. */
    val packaged: Int get() = packages?.running ?: 0

    suspend fun serve(call: ApplicationCall, id: String, epoch: Int, token: String) {
        try {
            serveWithin(call, id, epoch, token)
        } catch (e: CancellationException) {
            // The session let this request go (released, retuned, or replaced by the player's next request), which
            // cancels only its scope; the call itself still runs, and a bare cancellation would answer 500. Once the
            // player has had part of the answer, there is nothing left to tell it.
            currentCoroutineContext().ensureActive()
            if (call.response.isCommitted) throw e
            throw withContext(NonCancellable) { sessions.media.letGo(id) }
        }
    }

    private suspend fun serveWithin(call: ApplicationCall, id: String, epoch: Int, token: String) = coroutineScope {
        val job = coroutineContext[Job]!!
        val resource = sessions.media.acquire(id, epoch, token, job, call.request.origin.remoteAddress)
        try {
            if (resource.url.startsWith(
                    PackagedStreams.SCHEME,
                )
            ) {
                return@coroutineScope servePackagedSegment(call, id, epoch, resource)
            }
            // A native source resolves to its playable URL now; the headers it needs go with every request to it. One
            // that fails moves the session to the channel's next source, and the 410 sends the player to it.
            val stream = try {
                feeds.playable(resource.offering)
            } catch (_: ProviderFailure) {
                if (sessions.media.sourceFailed(id, epoch, job)) {
                    throw ApiFailure(
                        HttpStatusCode.Gone,
                        Wire.ErrorCode.STREAM_CHANGED,
                        "The source failed; fetch the current session descriptor",
                        true,
                    )
                }
                throw ApiFailure(
                    HttpStatusCode.BadGateway,
                    "UPSTREAM_UNAVAILABLE",
                    "This channel isn't available right now",
                    true,
                )
            }
            when {
                resource.packaged -> servePackaged(call, id, epoch, resource, stream)
                resource.shared -> serveShared(call, id, epoch, job, resource, stream)
                else -> serveDirect(call, id, epoch, job, resource, stream)
            }
        } finally {
            withContext(NonCancellable) { sessions.media.finished(id, job) }
        }
    }

    /**
     * LL-HLS players append delivery directives to the playlist URL they were given; forward them to the real playlist.
     */
    private fun withDeliveryDirectives(url: String, call: ApplicationCall): String {
        val directives =
            listOf("_HLS_msn", "_HLS_part", "_HLS_skip").mapNotNull { name ->
                call.request.queryParameters[name]?.takeIf { it.matches(DIRECTIVE_VALUE) }?.let { "$name=$it" }
            }
        if (directives.isEmpty()) return url
        return url + (if (URI(url).rawQuery == null) "?" else "&") + directives.joinToString("&")
    }

    /**
     * The descriptor promised one protocol and the body carries the other: bump the epoch so the player re-reads it.
     */
    private suspend fun protocolMismatch(id: String, epoch: Int, job: Job, protocol: String): Nothing {
        sessions.media.protocolObserved(id, epoch, protocol, job)
        throw ApiFailure(
            HttpStatusCode.Gone,
            Wire.ErrorCode.STREAM_CHANGED,
            "Stream protocol changed; fetch the current session descriptor",
            true,
        )
    }

    /**
     * A reader of [offering]'s one upstream MPEG-TS connection, opening it from [stream] when nobody else reads it;
     * players and recordings of the same source share it. The reader buffers [chunks] chunks, the default when null.
     */
    internal suspend fun joinShared(offering: Offering, stream: ResolvedStream, chunks: Int? = null): TsFanout.Reader =
        shared.join(offering.id, chunks) { consume ->
            permits.withPermit {
                http.withUpstreamResponse(
                    stream.url,
                    stream.url,
                    stream.headers,
                    unbounded = true,
                    policy = feeds.upstream,
                ) { response ->
                    if (response.status != HttpStatusCode.OK) throw UpstreamStatusException(response.status.value)
                    consume(response.bodyAsChannel())
                }
            }
        }

    /** The live playlist of a session whose MPEG-TS source goes out packaged as HLS. */
    private suspend fun servePackaged(
        call: ApplicationCall,
        id: String,
        epoch: Int,
        resource: MediaResource,
        stream: ResolvedStream,
    ) {
        val packages = packages ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown media")
        val text = packages.playlist(id, epoch, resource.offering, stream) { url ->
            sessions.media.resourcePath(id, epoch, url)
        }
        // AVFoundation shows captions only when a multivariant playlist declares them: its root goes out as one naming
        // the media playlist, which carries the same token with the media marker (see CaptionPlaylists).
        val declared = declaresCaptions(call, resource) && packages.carriesCaptions(id, epoch)
        val body = if (declared) {
            val variant = sessions.media.resourcePath(id, epoch, resource.offering.url + CaptionPlaylists.MEDIA)
            CaptionPlaylists.masterOf(variant)
        } else {
            text
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    /** A segment of a packaged playlist, from the package's directory. */
    private suspend fun servePackagedSegment(call: ApplicationCall, id: String, epoch: Int, resource: MediaResource) {
        val file = packages?.segment(id, epoch, resource.url)
            ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown media resource")
        val size = withContext(Dispatchers.IO) { Files.size(file) }
        resource.stats.bytes.addAndGet(size)
        resource.stats.segments.incrementAndGet()
        call.response.header(HttpHeaders.CacheControl, "private, max-age=60")
        call.respond(LocalPathContent(file, ContentType.parse("video/mp2t")))
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
                if (!job.isActive) throw withContext(NonCancellable) { sessions.media.letGo(id) }
                resource.stats.stalls.incrementAndGet()
                throw ApiFailure(
                    HttpStatusCode.BadGateway,
                    "UPSTREAM_UNAVAILABLE",
                    "This channel isn't available right now",
                    true,
                )
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytesWriter(contentType = ContentType.parse("video/mp2t")) {
                for (chunk in reader.chunks) {
                    writeFully(chunk)
                    resource.stats.bytes.addAndGet(chunk.size.toLong())
                    flush()
                }
            }
        } finally {
            withContext(NonCancellable) { reader.close() }
        }
    }

    /**
     * A media playlist that shows its source stalled moves the session to the channel's next source, and the 410
     * sends the player to it; with no other source left, the playlist goes out as it is.
     */
    private suspend fun failOverStalled(
        resource: MediaResource,
        text: String,
        source: URI,
        id: String,
        epoch: Int,
        job: Job,
    ) {
        if (!resource.stalls.stalled(text, source)) return
        if (sessions.media.failover(id, epoch, job, RemuxWatch.STALL) == null) return
        throw ApiFailure(
            HttpStatusCode.Gone,
            Wire.ErrorCode.STREAM_CHANGED,
            "The source stalled; fetch the current session descriptor",
            true,
        )
    }

    /** A media playlist of [resource] from [source]: held over a lapse, failed over when stalled, its ads marked. */
    private suspend fun edited(
        resource: MediaResource,
        text: String,
        source: URI,
        id: String,
        epoch: Int,
        job: Job,
    ): String {
        failOverStalled(resource, text, source, id, epoch, job)
        return resource.playlists.steady(text, source).let { resource.ads?.annotate(it, source) ?: it }
    }

    private suspend fun serveDirect(
        call: ApplicationCall,
        id: String,
        epoch: Int,
        job: Job,
        resource: MediaResource,
        stream: ResolvedStream,
    ) {
        // The root token maps to the offering's own URL, which for a native source is the resolved one, and so does
        // the media playlist inside a root that declares captions; children are absolute already.
        val root = resource.url == resource.offering.url
        val rootMedia = root || resource.url.endsWith(CaptionPlaylists.MEDIA)
        val url = if (rootMedia) stream.url else resource.url
        val declaring = declaresCaptions(call, resource)
        val ads = resource.ads
        var tapped: TappedSegment? = null
        countStalls(call, job, resource.stats) {
            permits.withPermit {
                relayResource(
                    http, feeds.upstream, call, withDeliveryDirectives(url, call), stream,
                    path = { sessions.media.resourcePath(id, epoch, it) },
                    sent = { resource.stats.bytes.addAndGet(it) },
                    manifest = { text, source ->
                        val declared = if (declaring) {
                            captions.declared(
                                resource.offering.id,
                                text,
                                source,
                                stream,
                            )
                        } else {
                            null
                        }
                        declared ?: edited(resource, text, source, id, epoch, job)
                    },
                    sniffed = { protocol ->
                        if (rootMedia && protocol == "mpegts") protocolMismatch(id, epoch, job, "mpegts")
                    },
                    tap = ads?.let { { found -> tapped = found } },
                    keepBytes = audio != null && ads?.listening == true,
                    streamed = { resource.stats.segments.incrementAndGet() },
                )
            }
        }
        ads?.served(url, tapped?.cues)
        val bytes = tapped?.bytes
        // Read after the segment is answered, so the player never waits on it.
        if (ads != null && audio != null && bytes != null) {
            synchronized(ads) { ads.follower ?: AudioFollower(audio, ads, scope).also { ads.follower = it } }
                .offer(url, bytes)
        }
    }
}

/**
 * Runs [block], a media request, and counts a stall in [stats] when the upstream refused or dropped it before the
 * player was answered anything, while the request was still wanted and its stream had not moved on.
 */
private inline fun <T> countStalls(call: ApplicationCall, job: Job, stats: SessionStats, block: () -> T): T = try {
    block()
} catch (e: ApiFailure) {
    if (e.code != Wire.ErrorCode.STREAM_CHANGED && stalled(call, job)) stats.stalls.incrementAndGet()
    throw e
} catch (e: IOException) {
    if (stalled(call, job)) stats.stalls.incrementAndGet()
    throw e
} catch (e: UpstreamRejectedException) {
    if (stalled(call, job)) stats.stalls.incrementAndGet()
    throw e
}

/** Whether a media request that failed was still wanted and had answered nothing yet. */
private fun stalled(call: ApplicationCall, job: Job): Boolean = job.isActive && !call.response.isCommitted

/**
 * Whether [call] gets the captions in [resource] declared (see [CaptionPlaylists]): on the root playlist of the app's
 * own sessions, to AVFoundation, whose media requests say `AppleCoreMedia/1.0.0…` in their `User-Agent`.
 */
private fun declaresCaptions(call: ApplicationCall, resource: MediaResource): Boolean =
    resource.url == resource.offering.url && resource.ads != null &&
        call.request.headers[HttpHeaders.UserAgent].orEmpty().contains("AppleCoreMedia")

/** How much of a segment read for cues passes through at a time. */
private const val SEGMENT_READ = 64 * 1024

/** The most an HLS manifest may hold, as sent and once inflated. */
private const val MANIFEST_LIMIT = 2 * MIB

/** The most of a segment kept for [TappedSegment.bytes]; a longer one is not kept. */
private const val SEGMENT_KEEP_LIMIT = 32 * 1024 * 1024

/**
 * What [relayResource] read from a transport stream segment: its SCTE-35 [cues], and its
 * [bytes] when asked to keep them.
 */
internal class TappedSegment(val cues: SegmentCues, val bytes: ByteArray?)

/** A gzip-encoded resource is buffered to be inflated; keys are bytes, anything near this is not a key. */
private const val ENCODED_LIMIT = 8 * 1024 * 1024

/**
 * Answers [call] with one upstream resource, for a player's session or an FFmpeg lease alike. [url] is fetched with
 * [stream]'s headers, scoped to its origin, under [policy], following redirects by hand and checking every hop; the
 * call's `Range` goes along. A manifest comes back rewritten so every URI it names maps through [path] to this
 * server, a gzip-encoded small resource inflated, and anything else streamed as the upstream sent it. [manifest] may
 * add to a manifest's text, given the URL it came from, before the rewrite. [sniffed] sees
 * the protocol of the body's head before anything is answered, [sent] each count of bytes answered, and [refused]
 * an upstream status other than 200 or 206. Given [tap], a transport stream segment is read for SCTE-35 cues as it
 * streams (see [SpliceReader]), and [tap] gets what it carried once all of it is answered, with its bytes when
 * [keepBytes]. [streamed] hears of each resource streamed as the upstream sent it, a segment rather than a
 * manifest, with the bytes it answered.
 */
internal suspend fun relayResource(
    http: HttpClient,
    policy: UpstreamPolicy,
    call: ApplicationCall,
    url: String,
    stream: ResolvedStream,
    path: suspend (String) -> String,
    sent: (Long) -> Unit = {},
    refused: (Int) -> Nothing = {
        throw ApiFailure(
            HttpStatusCode.BadGateway,
            "UPSTREAM_UNAVAILABLE",
            "This channel isn't available right now",
            true,
        )
    },
    sniffed: suspend (String?) -> Unit = {},
    manifest: suspend (String, URI) -> String = { text, _ -> text },
    tap: ((TappedSegment) -> Unit)? = null,
    keepBytes: Boolean = false,
    streamed: (Long) -> Unit = {},
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
        var answered = 0L
        val relayed = RelayedBody(call, response, body, head) { count ->
            answered += count
            sent(count)
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        when {
            relayed.isManifest(protocol) -> relayed.manifest(manifest, path)

            relayed.isEncoded() -> relayed.encoded()

            else -> {
                relayed.streamed(tap?.takeIf { protocol == "mpegts" }, keepBytes)
                streamed(answered)
            }
        }
    }
}

/** An upstream [response] that [relayResource] answers [call] with: its [body], of which [head] is read already. */
private class RelayedBody(
    private val call: ApplicationCall,
    private val response: HttpResponse,
    private val body: ByteReadChannel,
    private val head: ByteArray,
    private val sent: (Long) -> Unit,
) {
    private val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';')
    private val source get() = URI(response.request.url.toString())

    /** Whether the body is an HLS manifest: its head says so, or names no protocol and its URL or type does. */
    fun isManifest(protocol: String?): Boolean = protocol == "hls" ||
        (protocol == null && (source.path.endsWith(".m3u8", true) || type.contains("mpegurl", true)))

    /** Whether the body is gzip-encoded: a CDN may gzip a key or a small resource on its own. */
    fun isEncoded(): Boolean = response.headers[HttpHeaders.ContentEncoding]?.contains("gzip", true) == true ||
        (isGzip(head) && !type.startsWith("video/", true))

    /** Answers with the manifest, inflated when gzipped, after [edit] and the rewrite that maps its URIs by [path]. */
    suspend fun manifest(edit: suspend (String, URI) -> String, path: suspend (String) -> String) {
        val raw = upTo(MANIFEST_LIMIT) {
            throw ApiFailure(HttpStatusCode.BadGateway, "INVALID_MANIFEST", "Manifest too large")
        }
        val bytes = if (isGzip(raw)) inflateHead(raw, MANIFEST_LIMIT) else raw
        val source = source
        val rewritten = HlsRewriter.rewrite(edit(bytes.toString(Charsets.UTF_8), source), source, path)
        sent(rewritten.length.toLong())
        call.respondText(rewritten, ContentType.parse("application/vnd.apple.mpegurl"))
    }

    /** Answers with the inflated bytes, the ones a reader would decrypt with. */
    suspend fun encoded() {
        val raw = upTo(ENCODED_LIMIT) {
            throw ApiFailure(HttpStatusCode.BadGateway, "UPSTREAM_UNAVAILABLE", "Encoded resource too large", true)
        }
        val plain = if (isGzip(raw)) inflateHead(raw, ENCODED_LIMIT * 8) else raw
        sent(plain.size.toLong())
        call.respondBytes(plain, contentType())
    }

    /**
     * Streams the body as the upstream sent it, with its range headers. Given [tap], a transport stream segment is
     * read for its cues as it passes, and [tap] gets them once all of it is answered.
     */
    suspend fun streamed(tap: ((TappedSegment) -> Unit)?, keepBytes: Boolean) {
        response.headers[HttpHeaders.ContentRange]?.let { call.response.header(HttpHeaders.ContentRange, it) }
        response.headers[HttpHeaders.AcceptRanges]?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
        val tapping = tap?.let { tsSyncOffset(head, 3) }?.let { Tapping(head, it, keepBytes) }
        call.respondBytesWriter(contentType = contentType(), status = response.status) {
            writeFully(head)
            sent(head.size.toLong())
            if (tapping == null) sent(body.copyTo(this)) else tapping.copy(body, this, sent)
        }
        if (tap != null && tapping != null) tap(tapping.finish())
    }

    private fun contentType(): ContentType = try {
        ContentType.parse(type.ifBlank { "video/mp2t" })
    } catch (_: Exception) {
        ContentType.Application.OctetStream
    }

    /** The whole body, up to [limit] bytes; [tooLarge] when it holds more. */
    private suspend fun upTo(limit: Int, tooLarge: () -> Nothing): ByteArray {
        // One byte past the limit tells a body at the limit from one too large.
        val raw = head + body.readBuffer(limit + 1L - head.size).readByteArray()
        return if (raw.size > limit) tooLarge() else raw
    }
}

/**
 * Reads a transport stream segment for SCTE-35 cues as it passes (see [SpliceReader]), from its first sync byte at
 * [sync] in [head], and keeps its bytes when [keepBytes], up to [SEGMENT_KEEP_LIMIT].
 */
private class Tapping(head: ByteArray, sync: Int, keepBytes: Boolean) {
    private val reader = SpliceReader().apply { feed(head, sync) }
    private var kept = if (keepBytes) ByteArrayOutputStream().apply { write(head, sync, head.size - sync) } else null

    /** Copies the rest of the segment [from] the upstream [to] the player, telling [sent] each count of bytes. */
    suspend fun copy(from: ByteReadChannel, to: ByteWriteChannel, sent: (Long) -> Unit) {
        val buffer = ByteArray(SEGMENT_READ)
        var count = from.readAvailable(buffer)
        while (count >= 0) {
            if (count > 0) {
                to.writeFully(buffer, 0, count)
                reader.feed(buffer, 0, count)
                kept?.write(buffer, 0, count)
                if ((kept?.size() ?: 0) > SEGMENT_KEEP_LIMIT) kept = null
                sent(count.toLong())
            }
            count = from.readAvailable(buffer)
        }
    }

    fun finish(): TappedSegment = TappedSegment(reader.finish(), kept?.toByteArray())
}
