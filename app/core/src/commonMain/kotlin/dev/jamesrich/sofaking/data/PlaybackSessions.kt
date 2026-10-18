package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    suspend fun configure(api: SofaKingApi?) {
        release(forget = true)
        mutex.withLock { this.api = api }
    }

    /**
     * The stream for [channelId]: the held session re-read when it is already this channel's, otherwise a new
     * session after the held one is released. A caller canceled while the server answers releases what it opened.
     */
    suspend fun tune(channelId: String, intent: PlaybackIntent): PlaybackStream = mutex.withLock {
        val api = api ?: throw IOException("No server configured")
        val held = _current.value
        if (held != null && held.channelId == channelId) {
            val reread = reread(api, held)
            if (reread != null) return@withLock if (reread.intent == intent) reread else publish(api.updateSession(reread.sessionId, intent.wire), intent)
        } else if (held != null) {
            releaseHeld(api, held)
        }
        open(api, channelId, intent)
    }

    /** Changes the held session's intent in place; nothing happens when the session is not [channelId]'s or already [intent]. */
    suspend fun setIntent(channelId: String, intent: PlaybackIntent) {
        mutex.withLock {
            val api = api ?: return
            val held = _current.value?.takeIf { it.channelId == channelId && it.intent != intent } ?: return
            publish(api.updateSession(held.sessionId, intent.wire), intent)
        }
    }

    /** Releases the held session. It can be reopened with [reopen] unless [forget] is set. */
    suspend fun release(forget: Boolean = false): Unit = withContext(NonCancellable) {
        mutex.withLock {
            val held = _current.value
            if (forget) resumable = null else if (held != null) resumable = resumableOf(held)
            val api = api
            if (held != null && api != null) releaseHeld(api, held)
        }
    }

    /** [release] on this instance's own scope, for callers that cannot suspend or whose scope may already be canceled. */
    fun releaseAsync() {
        scope.launch { release() }
    }

    /**
     * Opens a fresh session for what was released last: the channel with its intent, or the library item where
     * the player last was in it. Null when there is nothing to return to.
     */
    suspend fun reopen(): PlaybackStream? = when (val last = mutex.withLock { resumable }) {
        null -> null
        is Resumable.Live -> tune(last.channelId, last.intent)
        is Resumable.Item -> play(last.itemId, last.profile, mutex.withLock { itemPositionMs }, last.audioIndex)
    }

    /**
     * Plays the library item [itemId] from [startMs], as [profile] can: the file as it is, or HLS the server copies
     * or converts it into from that point. The held session, live or not, is released first.
     */
    suspend fun play(itemId: String, profile: PlaybackProfile, startMs: Long = 0, audioIndex: Int? = null): PlaybackStream = mutex.withLock {
        val api = api ?: throw IOException("No server configured")
        _current.value?.let { releaseHeld(api, it) }
        itemPositionMs = startMs.coerceAtLeast(0)
        val request = ItemRequest(itemId, profile, audioIndex)
        val opened = withContext(NonCancellable) {
            withTimeoutOrNull(openTimeout) { api.openVod(request.dto(itemPositionMs)) }
                ?: throw SessionTimeoutException("session open timed out")
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { withTimeout(RELEASE_TIMEOUT_MS) { api.releaseVod(opened.id) } } }
            throw e
        }
        val stream = publishItem(api, opened, request, itemPositionMs)
        renewal?.cancel()
        renewal = scope.launch { renewItemLoop(api, opened.id, request) }
        stream
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

    /** Releases the held session and ends the renewal loop; the instance is unusable afterwards. */
    fun close() {
        scope.launch { release(forget = true) }.invokeOnCompletion { scope.cancel() }
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

    private suspend fun open(api: SofaKingApi, channelId: String, intent: PlaybackIntent): PlaybackStream {
        // Opened whole: a zap that cancels this mid-request still learns which session it must release.
        // A timeout is an IOException, not a cancellation: the caller backs off and retries instead of dying silently.
        val opened = withContext(NonCancellable) {
            withTimeoutOrNull(openTimeout) { api.openSession(channelId, intent.wire, newIdempotencyKey()) }
                ?: throw SessionTimeoutException("session open timed out")
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { withTimeout(RELEASE_TIMEOUT_MS) { api.releaseSession(opened.id) } } }
            throw e
        }
        val stream = publish(opened, intent)
        renewal?.cancel()
        renewal = scope.launch { renewLoop(api, opened.id) }
        return stream
    }

    private fun publish(session: PlaybackSessionDto, intent: PlaybackIntent): PlaybackStream {
        val api = api ?: throw IOException("No server configured")
        val stream = PlaybackStream(
            channelId = session.channelId,
            sessionId = session.id,
            url = api.mediaUrl(session.descriptor),
            mimeType = mediaMimeType(session.descriptor),
            epoch = session.descriptor.epoch,
            intent = PlaybackIntent.fromWire(session.intent) ?: intent,
            providerId = session.providerId,
        )
        _current.value = stream
        return stream
    }

    private fun publishItem(api: SofaKingApi, session: VodSessionDto, request: ItemRequest, startAtMs: Long): PlaybackStream {
        val stream = PlaybackStream(
            channelId = "",
            sessionId = session.id,
            url = api.mediaUrl(session.descriptor),
            mimeType = mediaMimeType(session.descriptor),
            epoch = session.descriptor.epoch,
            intent = PlaybackIntent.WATCH,
            seekable = true,
            itemId = session.itemId,
            mode = session.mode,
            offsetMs = session.startMs,
            startAtMs = startAtMs,
            itemDurationMs = session.durationMs,
        )
        items[session.id] = request
        _current.value = stream
        return stream
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
     * Renews [sessionId] until it is no longer held. A renewal that comes back with a new epoch publishes the
     * replacement; one the server has dropped (evicted at capacity, or expired while unreachable) opens a new
     * session for the same channel. Transport failures wait for the next tick, inside the lease.
     */
    private suspend fun renewLoop(api: SofaKingApi, sessionId: String) {
        while (true) {
            delay(renewInterval)
            val held = _current.value?.takeIf { it.sessionId == sessionId } ?: return
            try {
                val renewed = api.updateSession(sessionId, null)
                mutex.withLock {
                    if (_current.value?.sessionId != sessionId) return
                    val stream = publish(renewed, held.intent)
                    if (stream.url != held.url) _replaced.tryEmit(stream)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SofaKingException) {
                if (e.status != 410) continue
                mutex.withLock {
                    if (_current.value?.sessionId != sessionId) return
                    _current.value = null
                    // A reopen that fails leaves no session; the player's own failure path tunes again with backoff.
                    val reopened = try {
                        withTimeoutOrNull(openTimeout) { api.openSession(held.channelId, held.intent.wire, newIdempotencyKey()) }
                            ?: throw SessionTimeoutException("session reopen timed out")
                    } catch (_: IOException) {
                        return
                    }
                    _replaced.tryEmit(publish(reopened, held.intent))
                    renewal = scope.launch { renewLoop(api, reopened.id) }
                }
                return
            } catch (_: IOException) {
                continue
            }
        }
    }

    /**
     * Renews the item session [sessionId] until it is no longer held. One the server has dropped (expired while
     * the device was unreachable, or the server restarted) opens a new session where the player last was.
     */
    private suspend fun renewItemLoop(api: SofaKingApi, sessionId: String, request: ItemRequest) {
        while (true) {
            delay(renewInterval)
            val held = _current.value?.takeIf { it.sessionId == sessionId } ?: return
            try {
                val renewed = api.updateVod(sessionId, null)
                mutex.withLock {
                    if (_current.value?.sessionId != sessionId) return
                    val stream = publishItem(api, renewed, request, held.startAtMs)
                    if (stream.url != held.url) _replaced.tryEmit(stream)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SofaKingException) {
                if (e.status != 410) continue
                mutex.withLock {
                    if (_current.value?.sessionId != sessionId) return
                    _current.value = null
                    items.remove(sessionId)
                    val start = itemPositionMs
                    val reopened = try {
                        withTimeoutOrNull(openTimeout) { api.openVod(request.dto(start)) } ?: throw SessionTimeoutException("session reopen timed out")
                    } catch (_: IOException) {
                        return
                    }
                    _replaced.tryEmit(publishItem(api, reopened, request, start))
                    renewal = scope.launch { renewItemLoop(api, reopened.id, request) }
                }
                return
            } catch (_: IOException) {
                continue
            }
        }
    }

    /** A library item as it was asked for: the same item, profile and audio stream reopen it. */
    private data class ItemRequest(val itemId: String, val profile: PlaybackProfile, val audioIndex: Int?) {
        fun dto(startMs: Long) = OpenVodPlaybackDto(
            itemId = itemId,
            profile = ClientProfileDto(profile.containers, profile.videoCodecs, profile.audioCodecs, profile.maxHeight, profile.hdr),
            startMs = startMs,
            audioIndex = audioIndex,
        )
    }

    private sealed interface Resumable {
        data class Live(val channelId: String, val intent: PlaybackIntent) : Resumable
        data class Item(val request: ItemRequest) : Resumable {
            val itemId get() = request.itemId
            val profile get() = request.profile
            val audioIndex get() = request.audioIndex
        }
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 8_000L
    }
}
