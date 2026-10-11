package dev.jamesrich.sofaking.data

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
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a playback session is for; a preview holds a 45 s lease and is the first evicted at capacity, a watch holds 90 s. */
enum class PlaybackIntent(internal val wire: String) {
    PREVIEW("preview"),
    WATCH("watch");

    internal companion object {
        fun fromWire(value: String): PlaybackIntent? = entries.firstOrNull { it.wire == value }
    }
}

/** The stream the player should hold for [channelId]. [url] changes whenever the server replaces the stream. */
data class PlaybackStream(
    val channelId: String,
    val sessionId: String,
    val url: String,
    val mimeType: String,
    val epoch: Int,
    val intent: PlaybackIntent,
)

/**
 * The one playback session the app holds on the Sofa King server. The guide preview opens it; OK promotes the
 * same session to watch, so the stream the player already has continues. The lease is renewed every
 * [renewInterval], inside the 45 s preview lease. Tuning another channel releases the session before it.
 * Renewal publishes a replaced stream on [replaced] when the server bumps the epoch or dropped the session.
 */
class PlaybackSessions(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val renewInterval: Duration = 15.seconds,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
    private val openTimeout: Duration = 12.seconds,
) {
    private val mutex = Mutex()
    private var api: SofaKingApi? = null
    private var renewal: Job? = null
    private val _current = MutableStateFlow<PlaybackStream?>(null)
    private val _replaced = MutableSharedFlow<PlaybackStream>(extraBufferCapacity = 4)

    /** The channel and intent to return to after [release]; cleared by [configure] and `release(forget = true)`. */
    private var resumable: Pair<String, PlaybackIntent>? = null

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
            if (forget) resumable = null else if (held != null) resumable = held.channelId to held.intent
            val api = api
            if (held != null && api != null) releaseHeld(api, held)
        }
    }

    /** Opens a fresh session for the channel released last, with its intent; null when there is none to return to. */
    suspend fun reopen(): PlaybackStream? {
        val (channelId, intent) = mutex.withLock { resumable } ?: return null
        return tune(channelId, intent)
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
                ?: throw InterruptedIOException("session open timed out")
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
        )
        _current.value = stream
        return stream
    }

    /** The server forgets the session once its lease runs out, so a failed DELETE is not retried. */
    private suspend fun releaseHeld(api: SofaKingApi, held: PlaybackStream) {
        renewal?.cancel()
        _current.value = null
        withContext(NonCancellable) { runCatching { withTimeout(RELEASE_TIMEOUT_MS) { api.releaseSession(held.sessionId) } } }
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
                            ?: throw InterruptedIOException("session reopen timed out")
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

    private companion object {
        const val RELEASE_TIMEOUT_MS = 8_000L
    }
}
