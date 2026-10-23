package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The server did not answer a session open in time; an [IOException], so the caller backs off and retries. */
class SessionTimeoutException(message: String) : IOException(message)

/** What a playback session is for; a preview holds a 45 s lease and is the first evicted at capacity, a watch holds 90 s. */
enum class PlaybackIntent(internal val wire: String) {
    PREVIEW(Wire.Intent.PREVIEW),
    WATCH(Wire.Intent.WATCH);

    internal companion object {
        fun fromWire(value: String): PlaybackIntent? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The stream the player should hold for [channelId], or for the library item [itemId]. [url] changes whenever the
 * server replaces the stream.
 */
/** The picture is in an ad break; [remainingMs] is how long the break has left, when the playlist says. */
data class AdBreakNow(val remainingMs: Long?)

data class PlaybackStream(
    /** The live channel; empty for a library item. */
    val channelId: String,
    val sessionId: String,
    val url: String,
    val mimeType: String,
    val epoch: Int,
    val intent: PlaybackIntent,
    /** The server's source for the stream, as its lineup names it; empty from a server that does not say. */
    val providerId: String = "",
    /** True for a recording or library file the viewer can move through; a live channel never is. */
    val seekable: Boolean = false,
    /** The library item this stream plays; null for a live channel. */
    val itemId: String? = null,
    /** How the server plays the item, a `Wire.VodMode` value; empty for a live channel. */
    val mode: String = "",
    /**
     * The item's time at the stream's 0. A converted stream starts where it was asked to, so its own position plus
     * this is the item's; a file played as it is starts at 0.
     */
    val offsetMs: Long = 0,
    /** Where in the item to begin: once the stream at [url] is loaded the player seeks to this less [offsetMs]. */
    val startAtMs: Long = 0,
    /** The item's whole length when the server knows it; a converted stream's own length grows as it is written. */
    val itemDurationMs: Long? = null,
)

/**
 * The one playback session the app holds on the Sofa King server, a live channel's or a library item's. The guide
 * preview opens it; OK promotes the same session to watch, so the stream the player already has continues. The
 * lease is renewed every [renewInterval], inside the 45 s preview lease. Tuning another channel or playing an item
 * releases the session before it. Renewal publishes a replaced stream on [replaced] when the server bumps the epoch
 * or dropped the session.
 */
class PlaybackSessions(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val renewInterval: Duration = 15.seconds,
    private val newIdempotencyKey: () -> String = { Uuid.random().toString() },
    private val openTimeout: Duration = 12.seconds,
) {
    private val mutex = Mutex()
    private var api: SofaKingApi? = null
    private var renewal: Job? = null
    private val _current = MutableStateFlow<PlaybackStream?>(null)
    private val _replaced = MutableSharedFlow<PlaybackStream>(extraBufferCapacity = 4)

    /** What to return to after [release]; cleared by [configure] and `release(forget = true)`. */
    private var resumable: Resumable? = null

    /** The held library item's position in the item, as the player last reported it; where a reopen starts. */
    @Volatile private var itemPositionMs = 0L

    /** The session held now, or null between channels, after [release] and before the server is configured. */
    val current: StateFlow<PlaybackStream?> = _current.asStateFlow()

    /** Streams the server replaced under a held session, which the player must reopen. */
    val replaced: SharedFlow<PlaybackStream> = _replaced.asSharedFlow()

    /** Points at a server, releasing whatever the previous one held; null until Settings names a valid server. */
    suspend fun configure(api: SofaKingApi?): Unit = withContext(NonCancellable) {
        // One lock for both, so no tune queued behind the release opens a session on the old server.
        mutex.withLock {
            resumable = null
            val previous = this@PlaybackSessions.api
            val held = _current.value
            if (held != null && previous != null) releaseHeld(previous, held)
            this@PlaybackSessions.api = api
        }
    }

    /**
     * The stream for [channelId]: the held session re-read when it is already this channel's, otherwise a new
     * session after the held one is released. A caller canceled while the server answers releases what it opened.
     */
    suspend fun tune(channelId: String, intent: PlaybackIntent): PlaybackStream = mutex.withLock {
        val api = api ?: throw IOException("No server configured")
        // A tune supersedes what an earlier stop left to reopen, even when this open fails.
        resumable = null
        val held = _current.value
        if (held != null && held.channelId == channelId) {
            val reread = reread(api, held)
            if (reread != null) return@withLock if (reread.intent == intent) reread else publish(api.updateSession(reread.sessionId, intent.wire), intent)
        } else if (held != null) {
            releaseHeld(api, held)
        }
        open(api, Live(channelId, intent))
    }

    /** Changes the held session's intent in place; nothing happens when the session is not [channelId]'s or already [intent]. */
    /**
     * The ad break the held live channel's player shows, as the server places it from the segments the player fetched
     * and [aheadMs], what it has loaded past the picture; null outside one, between channels and before a server is set.
     */
    suspend fun adBreak(aheadMs: Long): AdBreakNow? {
        val api = api ?: return null
        val held = _current.value?.takeIf { it.itemId == null && !it.seekable } ?: return null
        val state = api.adBreak(held.sessionId, aheadMs)
        return if (state.inBreak) AdBreakNow(state.remainingMs) else null
    }

    suspend fun setIntent(channelId: String, intent: PlaybackIntent) {
        mutex.withLock {
            val api = api ?: return
            val held = _current.value?.takeIf { it.channelId == channelId && it.intent != intent } ?: return
            publish(api.updateSession(held.sessionId, intent.wire), intent)
        }
    }

    /**
     * Releases the held session. It can be reopened with [reopen] unless [forget] is set. With [itemId], only that
     * item's session is released, so a page that leaves after the next one opened leaves the next one's alone.
     */
    suspend fun release(forget: Boolean = false, itemId: String? = null): Unit = withContext(NonCancellable) {
        mutex.withLock {
            val held = _current.value
            if (itemId != null && held?.itemId != itemId) return@withLock
            if (forget) resumable = null else if (held != null) resumable = resumableOf(held)
            val api = api
            if (held != null && api != null) releaseHeld(api, held)
        }
    }

    /** [release] on this instance's own scope, for callers that cannot suspend or whose scope may already be canceled. */
    fun releaseAsync(forget: Boolean = false, itemId: String? = null) {
        scope.launch { release(forget, itemId) }
    }

    /**
     * The DELETE that frees the held session, for a page being closed: it cannot wait for [release], whose request
     * would never leave, so it sends this itself as the page goes. Null when nothing is held. Nothing here changes; a
     * page the browser keeps and shows again finds its session gone and reopens it as after any dropped session.
     */
    fun exitRelease(): SessionRelease? {
        val api = api ?: return null
        val held = _current.value ?: return null
        return api.releaseRequest(held.sessionId, vod = held.itemId != null)
    }

    /**
     * Opens a fresh session for what was released last: the channel with its intent, or the library item where
     * the player last was in it. Null when there is nothing to return to.
     */
    suspend fun reopen(): PlaybackStream? = when (val last = mutex.withLock { resumable }) {
        null -> null
        is Resumable.Live -> tune(last.channelId, last.intent)
        is Resumable.Item -> openItem(last.request, mutex.withLock { itemPositionMs })
    }

    /**
     * Plays the library item [itemId] from [startMs], as [profile] can: the file as it is, or HLS the server copies
     * or converts it into from that point, with the audio track [audioIndex] and the picture subtitle track
     * [subtitleIndex] drawn in. The held session, live or not, is released first.
     */
    suspend fun play(itemId: String, profile: PlaybackProfile, startMs: Long = 0, audioIndex: Int? = null, subtitleIndex: Int? = null): PlaybackStream =
        openItem(ItemRequest(itemId, profile, audioIndex, subtitleIndex), startMs)

    /**
     * Plays the recording [recordingId] from [startMs] as [play] plays a library item; one still recording follows
     * the file as it grows. The stream's [PlaybackStream.itemId] is the recording's id.
     */
    suspend fun playRecording(recordingId: String, profile: PlaybackProfile, startMs: Long = 0): PlaybackStream =
        openItem(ItemRequest(recordingId, profile, audioIndex = null, subtitleIndex = null, recording = true), startMs)

    private suspend fun openItem(request: ItemRequest, startMs: Long): PlaybackStream = mutex.withLock {
        val api = api ?: throw IOException("No server configured")
        resumable = null
        _current.value?.let { releaseHeld(api, it) }
        itemPositionMs = startMs.coerceAtLeast(0)
        open(api, Item(request, itemPositionMs))
    }

    /**
     * Restarts the held item's converted stream at [positionMs] in the item, for a seek past what the server has
     * written; the new stream has a new [PlaybackStream.url]. A file played as it is seeks in the player instead, so
     * this returns it unchanged. Null when no item is held.
     */
    suspend fun restartAt(positionMs: Long): PlaybackStream? = mutex.withLock {
        val api = api ?: return null
        val held = _current.value?.takeIf { it.itemId != null } ?: return null
        val request = items[held.sessionId] ?: return null
        val start = positionMs.coerceAtLeast(0)
        itemPositionMs = start
        if (held.mode == Wire.VodMode.DIRECT) return held
        publishItem(api, api.updateVod(held.sessionId, start), request, start)
    }

    /** Records where the player is in the held item, so a reopen after [release] or a dropped session starts there. */
    fun reportPosition(positionMs: Long) {
        if (_current.value?.itemId != null) itemPositionMs = positionMs.coerceAtLeast(0)
    }

    /** The held session as the server has it now, or null once the server has dropped it. */
    private suspend fun reread(api: SofaKingApi, held: PlaybackStream): PlaybackStream? = try {
        publish(api.session(held.sessionId), held.intent)
    } catch (e: SofaKingException) {
        if (e.status != 410) throw e
        renewal?.cancel()
        _current.value = null
        null
    }

    /**
     * Opens a session of [kind] and holds it, renewing it until it is no longer held. It is opened whole: a caller
     * canceled mid-request still learns which session it must release, and releases it. A timeout is an
     * [IOException], not a cancellation, so the caller backs off and retries instead of dying silently.
     */
    private suspend fun open(api: SofaKingApi, kind: Kind): PlaybackStream {
        val opened = withContext(NonCancellable) {
            withTimeoutOrNull(openTimeout) { kind.open(api) } ?: throw SessionTimeoutException("session open timed out")
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { withTimeout(RELEASE_TIMEOUT_MS) { kind.release(api, opened.sessionId) } } }
            throw e
        }
        return hold(api, opened, kind)
    }

    /** Publishes [stream], a new session of [kind], and renews it from now on in place of whatever was renewed. */
    private fun hold(api: SofaKingApi, stream: PlaybackStream, kind: Kind): PlaybackStream {
        if (kind is Item) items[stream.sessionId] = kind.request
        _current.value = stream
        renewal?.cancel()
        renewal = scope.launch { renewLoop(api, stream.sessionId, kind) }
        return stream
    }

    private fun publish(session: PlaybackSessionDto, intent: PlaybackIntent): PlaybackStream {
        val api = api ?: throw IOException("No server configured")
        return liveStream(api, session, intent).also { _current.value = it }
    }

    private fun publishItem(api: SofaKingApi, session: VodSessionDto, request: ItemRequest, startAtMs: Long): PlaybackStream {
        items[session.id] = request
        return itemStream(api, session, request.itemId, startAtMs).also { _current.value = it }
    }

    /** What each item session was opened with, so a reopen asks for the same; only the held one is kept. */
    private val items = HashMap<String, ItemRequest>()

    private fun resumableOf(held: PlaybackStream): Resumable =
        items[held.sessionId]?.let { Resumable.Item(it) } ?: Resumable.Live(held.channelId, held.intent)

    /** The server forgets the session once its lease runs out, so a failed DELETE is not retried. */
    private suspend fun releaseHeld(api: SofaKingApi, held: PlaybackStream) {
        renewal?.cancel()
        _current.value = null
        val item = items.remove(held.sessionId) != null
        withContext(NonCancellable) {
            runCatching { withTimeout(RELEASE_TIMEOUT_MS) { if (item) api.releaseVod(held.sessionId) else api.releaseSession(held.sessionId) } }
        }
    }

    /**
     * Renews [sessionId], a session of [kind], until it is no longer held. A renewal that comes back with a new epoch
     * publishes the replacement; one the server has dropped (evicted at capacity, expired while unreachable, or lost
     * in a server restart) opens a new one in its place: the same channel, or the item where the player last was.
     * Transport failures wait for the next tick, inside the lease.
     */
    private suspend fun renewLoop(api: SofaKingApi, sessionId: String, kind: Kind) {
        while (true) {
            delay(renewInterval)
            val held = _current.value?.takeIf { it.sessionId == sessionId } ?: return
            try {
                val renewed = kind.renew(api, held)
                mutex.withLock {
                    if (_current.value?.sessionId != sessionId) return
                    // A restart, intent change or re-read published a newer stream while this renewal was out; the server
                    // may have answered the renewal before that, so its answer is stale and the next renewal reads afresh.
                    if (_current.value != held) continue
                    _current.value = renewed
                    if (renewed.url != held.url) _replaced.tryEmit(renewed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SofaKingException) {
                if (e.status != 410) continue
                mutex.withLock {
                    if (_current.value?.sessionId != sessionId) return
                    _current.value = null
                    items.remove(sessionId)
                    val again = kind.reopening(held, itemPositionMs)
                    // A reopen that fails leaves no session; the player's own failure path tunes again with backoff.
                    val reopened = try {
                        withTimeoutOrNull(openTimeout) { again.open(api) } ?: throw SessionTimeoutException("session reopen timed out")
                    } catch (_: IOException) {
                        return
                    }
                    _replaced.tryEmit(hold(api, reopened, again))
                }
                return
            } catch (_: IOException) {
                continue
            }
        }
    }

    /** What a session plays, and how the server opens, renews and releases it: a live channel's or a library item's. */
    private sealed interface Kind {
        /** Opens a new session, as the stream it gives. */
        suspend fun open(api: SofaKingApi): PlaybackStream

        /** Renews the lease on [held], as the stream the server has for it now. */
        suspend fun renew(api: SofaKingApi, held: PlaybackStream): PlaybackStream

        suspend fun release(api: SofaKingApi, sessionId: String)

        /** What opens in place of [held] once the server dropped it, the player last at [positionMs] in an item. */
        fun reopening(held: PlaybackStream, positionMs: Long): Kind
    }

    private inner class Live(private val channelId: String, private val intent: PlaybackIntent) : Kind {
        override suspend fun open(api: SofaKingApi) = liveStream(api, api.openSession(channelId, intent.wire, newIdempotencyKey()), intent)

        override suspend fun renew(api: SofaKingApi, held: PlaybackStream) = liveStream(api, api.updateSession(held.sessionId, null), held.intent)

        override suspend fun release(api: SofaKingApi, sessionId: String) = api.releaseSession(sessionId)

        override fun reopening(held: PlaybackStream, positionMs: Long): Kind = Live(held.channelId, held.intent)
    }

    private class Item(val request: ItemRequest, private val startMs: Long) : Kind {
        override suspend fun open(api: SofaKingApi) = itemStream(api, api.openVod(request.dto(startMs)), request.itemId, startMs)

        override suspend fun renew(api: SofaKingApi, held: PlaybackStream) = itemStream(api, api.updateVod(held.sessionId, null), request.itemId, held.startAtMs)

        override suspend fun release(api: SofaKingApi, sessionId: String) = api.releaseVod(sessionId)

        override fun reopening(held: PlaybackStream, positionMs: Long): Kind = Item(request, positionMs)
    }

    /** A library item or a [recording] as it was asked for: the same item, profile and tracks reopen it. */
    private data class ItemRequest(
        val itemId: String,
        val profile: PlaybackProfile,
        val audioIndex: Int?,
        val subtitleIndex: Int?,
        val recording: Boolean = false,
    ) {
        fun dto(startMs: Long) = OpenVodPlaybackDto(
            itemId = if (recording) "" else itemId,
            profile = ClientProfileDto(profile.containers, profile.videoCodecs, profile.audioCodecs, profile.maxHeight, profile.hdr, profile.deepColor),
            startMs = startMs,
            audioIndex = audioIndex,
            recordingId = itemId.takeIf { recording },
            subtitleIndex = subtitleIndex,
            wholePlaylist = profile.wholePlaylist,
        )
    }

    private sealed interface Resumable {
        data class Live(val channelId: String, val intent: PlaybackIntent) : Resumable
        data class Item(val request: ItemRequest) : Resumable
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 8_000L
    }
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
