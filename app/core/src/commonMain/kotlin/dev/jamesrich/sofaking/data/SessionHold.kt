package dev.jamesrich.sofaking.data

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/** How long a release waits for the server before it lets the session's lease run out instead. */
private const val RELEASE_TIMEOUT_MS = 8_000L

/**
 * The session [PlaybackSessions] holds and its renewal: opened, published, renewed every [renewInterval] in [scope]
 * and released here, under [mutex], on the server [api] names.
 */
internal class SessionHold(
    val scope: CoroutineScope,
    private val renewInterval: Duration,
    val newIdempotencyKey: () -> String,
    private val openTimeout: Duration,
) {
    val mutex = Mutex()
    var api: SofaKingApi? = null
    private var renewal: Job? = null

    /** The session held now, as [PlaybackSessions.current] shows it. */
    val current = MutableStateFlow<PlaybackStream?>(null)

    /** Streams the server replaced under a held session, as [PlaybackSessions.replaced] shows them. */
    val replaced = MutableSharedFlow<PlaybackStream>(extraBufferCapacity = 4)

    /** What to return to after a release; cleared by a configure and `release(forget = true)`. */
    var resumable: Resumable? = null

    /** The held library item's position in the item, as the player last reported it; where a reopen starts. */
    @Volatile var itemPositionMs = 0L

    /** What each item session was opened with, so a reopen asks for the same; only the held one is kept. */
    val items = HashMap<String, ItemRequest>()

    /** The held session as the server has it now, or null once the server has dropped it. */
    suspend fun reread(api: SofaKingApi, held: PlaybackStream): PlaybackStream? = try {
        publish(api.session(held.sessionId), held.intent)
    } catch (e: SofaKingException) {
        if (e.statusCode != HttpStatusCode.Gone) throw e
        renewal?.cancel()
        current.value = null
        null
    }

    /**
     * Opens a session of [kind] and holds it, renewing it until it is no longer held. It is opened whole: a caller
     * canceled mid-request still learns which session it must release, and releases it. A timeout is an
     * [IOException], not a cancellation, so the caller backs off and retries instead of dying silently.
     */
    suspend fun open(api: SofaKingApi, kind: SessionKind): PlaybackStream {
        val opened = withContext(NonCancellable) {
            withTimeoutOrNull(openTimeout) { kind.open(api) } ?: throw SessionTimeoutException("session open timed out")
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { withTimeout(RELEASE_TIMEOUT_MS) { kind.release(api, opened.sessionId) } }
            }
            throw e
        }
        return hold(api, opened, kind)
    }

    /** Publishes [stream], a new session of [kind], and renews it from now on in place of whatever was renewed. */
    private fun hold(api: SofaKingApi, stream: PlaybackStream, kind: SessionKind): PlaybackStream {
        if (kind is ItemSessionKind) items[stream.sessionId] = kind.request
        current.value = stream
        renewal?.cancel()
        renewal = scope.launch { renewLoop(api, stream.sessionId, kind) }
        return stream
    }

    fun publish(session: PlaybackSessionDto, intent: PlaybackIntent): PlaybackStream {
        val api = api ?: throw IOException("No server configured")
        return liveStream(api, session, intent).also { current.value = it }
    }

    fun publishItem(api: SofaKingApi, session: VodSessionDto, request: ItemRequest, startAtMs: Long): PlaybackStream {
        items[session.id] = request
        return itemStream(api, session, request.itemId, startAtMs).also { current.value = it }
    }

    /** The server forgets the session once its lease runs out, so a failed DELETE is not retried. */
    suspend fun releaseHeld(api: SofaKingApi, held: PlaybackStream) {
        renewal?.cancel()
        current.value = null
        val item = items.remove(held.sessionId) != null
        withContext(NonCancellable) {
            runCatching {
                withTimeout(RELEASE_TIMEOUT_MS) {
                    when {
                        held.isMultiview -> api.releaseMultiview(held.sessionId)
                        item -> api.releaseVod(held.sessionId)
                        else -> api.releaseSession(held.sessionId)
                    }
                }
            }
        }
    }

    /**
     * Renews [sessionId], a session of [kind], until it is no longer held. A renewal that comes back with a new epoch
     * publishes the replacement; one the server has dropped (evicted at capacity, expired while unreachable, or lost
     * in a server restart) opens a new one in its place: the same channel, or the item where the player last was.
     * Transport failures wait for the next tick, inside the lease.
     */
    private suspend fun renewLoop(api: SofaKingApi, sessionId: String, kind: SessionKind) {
        do {
            delay(renewInterval)
            val held = current.value?.takeIf { it.sessionId == sessionId } ?: return
        } while (renew(api, held, kind))
    }

    /** Renews [held] once; true while its session is still held, so renewing goes on. */
    private suspend fun renew(api: SofaKingApi, held: PlaybackStream, kind: SessionKind): Boolean = try {
        val renewed = kind.renew(api, held)
        mutex.withLock { publishRenewal(held, renewed) }
    } catch (e: SofaKingException) {
        val gone = e.statusCode == HttpStatusCode.Gone
        if (gone) mutex.withLock { reopen(api, held, kind) }
        !gone
    } catch (_: IOException) {
        true
    }

    /** Publishes [renewed] in place of [held]; false once [held]'s session is no longer the one held. */
    private fun publishRenewal(held: PlaybackStream, renewed: PlaybackStream): Boolean {
        val now = current.value
        if (now?.sessionId != held.sessionId) return false
        // A restart, intent change or re-read published a newer stream while this renewal was out; the server may
        // have answered the renewal before that, so its answer is stale and the next renewal reads afresh.
        if (now == held) {
            current.value = renewed
            if (renewed.url != held.url) replaced.tryEmit(renewed)
        }
        return true
    }

    /** Opens a new session in place of [held], which the server dropped, unless another one is held by now. */
    private suspend fun reopen(api: SofaKingApi, held: PlaybackStream, kind: SessionKind) {
        if (current.value?.sessionId != held.sessionId) return
        current.value = null
        items.remove(held.sessionId)
        val again = kind.reopening(held, itemPositionMs)
        // A reopen that fails leaves no session; the player's own failure path tunes again with backoff.
        val reopened = try {
            withTimeoutOrNull(openTimeout) { again.open(api) }
                ?: throw SessionTimeoutException("session reopen timed out")
        } catch (_: IOException) {
            return
        }
        replaced.tryEmit(hold(api, reopened, again))
    }
}

/** What a session plays, and how the server opens, renews and releases it: a live channel's or a library item's. */
internal sealed interface SessionKind {
    /** Opens a new session, as the stream it gives. */
    suspend fun open(api: SofaKingApi): PlaybackStream

    /** Renews the lease on [held], as the stream the server has for it now. */
    suspend fun renew(api: SofaKingApi, held: PlaybackStream): PlaybackStream

    suspend fun release(api: SofaKingApi, sessionId: String)

    /** What opens in place of [held] once the server dropped it, the player last at [positionMs] in an item. */
    fun reopening(held: PlaybackStream, positionMs: Long): SessionKind
}

internal class LiveSessionKind(
    private val channelId: String,
    private val intent: PlaybackIntent,
    private val newIdempotencyKey: () -> String,
) : SessionKind {
    override suspend fun open(api: SofaKingApi) =
        liveStream(api, api.openSession(channelId, intent.wire, newIdempotencyKey()), intent)

    override suspend fun renew(api: SofaKingApi, held: PlaybackStream) =
        liveStream(api, api.updateSession(held.sessionId, null), held.intent)

    override suspend fun release(api: SofaKingApi, sessionId: String) = api.releaseSession(sessionId)

    override fun reopening(held: PlaybackStream, positionMs: Long): SessionKind =
        LiveSessionKind(held.channelId, held.intent, newIdempotencyKey)
}

internal class ItemSessionKind(val request: ItemRequest, private val startMs: Long) : SessionKind {
    override suspend fun open(api: SofaKingApi) =
        itemStream(api, api.openVod(request.dto(startMs)), request.itemId, startMs)

    override suspend fun renew(api: SofaKingApi, held: PlaybackStream) =
        itemStream(api, api.updateVod(held.sessionId, null), request.itemId, held.startAtMs)

    override suspend fun release(api: SofaKingApi, sessionId: String) = api.releaseVod(sessionId)

    override fun reopening(held: PlaybackStream, positionMs: Long): SessionKind = ItemSessionKind(request, positionMs)
}

/** A multiview of [channelIds] with the sound of tile [audio]; a reopen keeps the tile the sound was last moved to. */
internal class MultiviewSessionKind(private val channelIds: List<String>, private val audio: Int) : SessionKind {
    override suspend fun open(api: SofaKingApi) = multiviewStream(api, api.openMultiview(channelIds, audio))

    override suspend fun renew(api: SofaKingApi, held: PlaybackStream) =
        multiviewStream(api, api.updateMultiview(held.sessionId, null))

    override suspend fun release(api: SofaKingApi, sessionId: String) = api.releaseMultiview(sessionId)

    override fun reopening(held: PlaybackStream, positionMs: Long): SessionKind =
        MultiviewSessionKind(held.tiles, held.audioTile)
}

/** A library item or a [recording] as it was asked for: the same item, profile and tracks reopen it. */
internal data class ItemRequest(
    val itemId: String,
    val profile: PlaybackProfile,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val recording: Boolean = false,
) {
    fun dto(startMs: Long) = OpenVodPlaybackDto(
        itemId = if (recording) "" else itemId,
        profile = ClientProfileDto(
            profile.containers,
            profile.videoCodecs,
            profile.audioCodecs,
            profile.maxHeight,
            profile.hdr,
            profile.deepColor,
        ),
        startMs = startMs,
        audioIndex = audioIndex,
        recordingId = itemId.takeIf { recording },
        subtitleIndex = subtitleIndex,
        wholePlaylist = profile.wholePlaylist,
    )
}

/** What a release leaves to reopen. */
internal sealed interface Resumable {
    data class Live(val channelId: String, val intent: PlaybackIntent) : Resumable
    data class Item(val request: ItemRequest) : Resumable
    data class Multiview(val channelIds: List<String>, val audio: Int) : Resumable
}

private fun liveStream(api: SofaKingApi, session: PlaybackSessionDto, intent: PlaybackIntent) = PlaybackStream(
    channelId = session.channelId,
    sessionId = session.id,
    url = api.mediaUrl(session.descriptor),
    mimeType = mediaMimeType(session.descriptor),
    epoch = session.descriptor.epoch,
    intent = PlaybackIntent.fromWire(session.intent) ?: intent,
    providerId = session.providerId,
)

private fun itemStream(api: SofaKingApi, session: VodSessionDto, itemId: String, startAtMs: Long) = PlaybackStream(
    channelId = "",
    sessionId = session.id,
    url = api.mediaUrl(session.descriptor),
    mimeType = mediaMimeType(session.descriptor),
    epoch = session.descriptor.epoch,
    intent = PlaybackIntent.WATCH,
    seekable = true,
    itemId = itemId,
    mode = session.mode,
    offsetMs = session.startMs,
    startAtMs = startAtMs,
    itemDurationMs = session.durationMs,
)

internal fun multiviewStream(api: SofaKingApi, session: MultiviewSessionDto) = PlaybackStream(
    channelId = session.channelIds.getOrElse(session.audio) { session.channelIds.first() },
    sessionId = session.id,
    url = api.mediaUrl(session.descriptor),
    mimeType = mediaMimeType(session.descriptor),
    epoch = session.descriptor.epoch,
    intent = PlaybackIntent.WATCH,
    tiles = session.channelIds,
    audioTile = session.audio,
)
