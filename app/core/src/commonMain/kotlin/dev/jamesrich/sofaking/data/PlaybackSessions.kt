package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The server did not answer a session open in time; an [IOException], so the caller backs off and retries. */
class SessionTimeoutException(message: String) : IOException(message)

/**
 * What a playback session is for; a preview holds a 45 s lease and is the first evicted at
 * capacity, a watch holds 90 s.
 */
enum class PlaybackIntent(internal val wire: String) {
    PREVIEW(Wire.Intent.PREVIEW),
    WATCH(Wire.Intent.WATCH),
    ;

    internal companion object {
        fun fromWire(value: String): PlaybackIntent? = entries.firstOrNull { it.wire == value }
    }
}

/** The picture is in an ad break; [remainingMs] is how long the break has left, when the playlist says. */
data class AdBreakNow(val remainingMs: Long?)

/**
 * A live session's media as the server's relay sent it: the rate over the last ten seconds or so, the media requests
 * the source refused or dropped, and how often the server moved the session to another source.
 */
data class StreamHealth(val bitrateKbps: Int, val stalls: Long, val sourceSwitches: Int)

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
    /**
     * A multiview's channels, as the server draws them left to right and top to bottom; empty for one channel or an
     * item. [channelId] is then the channel of [audioTile], whose sound plays.
     */
    val tiles: List<String> = emptyList(),
    val audioTile: Int = 0,
) {
    /** True for a multiview, several channels the server draws into this one stream. */
    val isMultiview: Boolean get() = tiles.isNotEmpty()
}

/** A live channel's session, as the guide and the live player hold it. */
interface LiveSessions {
    /**
     * The stream for [channelId]: the held session re-read when it is already this channel's, otherwise a new
     * session after the held one is released. A caller canceled while the server answers releases what it opened.
     */
    suspend fun tune(channelId: String, intent: PlaybackIntent): PlaybackStream

    /**
     * The ad break the held live channel's player shows, as the server places it from the segments the player fetched
     * and [aheadMs], what it has loaded past the picture; null outside one, between
     * channels and before a server is set.
     */
    suspend fun adBreak(aheadMs: Long): AdBreakNow?

    /**
     * Changes the held session's intent in place; nothing happens when the session is not [channelId]'s or already
     * [intent].
     */
    suspend fun setIntent(channelId: String, intent: PlaybackIntent)

    /**
     * What the server's relay has seen of the held live channel's session, for the stream card; null for anything
     * else held, before a server is set, and from a server that does not say.
     */
    suspend fun health(): StreamHealth?
}

/** A library item's or a recording's session, as the library player holds it. */
interface ItemSessions {
    /**
     * Plays the library item [itemId] from [startMs], as [profile] can: the file as it is, or HLS the server copies
     * or converts it into from that point, with the audio track [audioIndex] and the picture subtitle track
     * [subtitleIndex] drawn in. The held session, live or not, is released first.
     */
    suspend fun play(
        itemId: String,
        profile: PlaybackProfile,
        startMs: Long = 0,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
    ): PlaybackStream

    /**
     * Plays the recording [recordingId] from [startMs] as [play] plays a library item; one still recording follows
     * the file as it grows. The stream's [PlaybackStream.itemId] is the recording's id.
     */
    suspend fun playRecording(recordingId: String, profile: PlaybackProfile, startMs: Long = 0): PlaybackStream

    /**
     * Restarts the held item's converted stream at [positionMs] in the item, for a seek past what the server has
     * written; the new stream has a new [PlaybackStream.url]. A file played as it is seeks in the player instead, so
     * this returns it unchanged. Null when no item is held.
     */
    suspend fun restartAt(positionMs: Long): PlaybackStream?

    /** Records where the player is in the held item, so a reopen after a release or a dropped session starts there. */
    fun reportPosition(positionMs: Long)
}

/** A multiview's session, as the multiview screen holds it. */
interface MultiviewSessions {
    /**
     * Plays [channelIds], two to four, as one picture the server draws, with the sound of tile [audio]. The held
     * session, whatever it plays, is released first.
     */
    suspend fun multiview(channelIds: List<String>, audio: Int): PlaybackStream

    /**
     * Moves the held multiview's sound to [tile]; the server starts its stream again, so the new one has a new
     * [PlaybackStream.url]. Null when no multiview is held.
     */
    suspend fun moveAudio(tile: Int): PlaybackStream?
}

/**
 * The one playback session the app holds on the Sofa King server, a live channel's or a library item's. The guide
 * preview opens it; OK promotes the same session to watch, so the stream the player already has continues. The
 * lease is renewed every `renewInterval`, inside the 45 s preview lease. Tuning another channel or playing an item
 * releases the session before it. Renewal publishes a replaced stream on [replaced] when the server bumps the epoch
 * or dropped the session.
 */
class PlaybackSessions private constructor(
    private val hold: SessionHold,
    private val playing: ItemPlaying = ItemPlaying(hold),
) : LiveSessions by LiveTuning(hold),
    ItemSessions by playing,
    MultiviewSessions by MultiviewPlaying(hold) {
    constructor(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        renewInterval: Duration = 15.seconds,
        newIdempotencyKey: () -> String = { Uuid.random().toString() },
        openTimeout: Duration = 12.seconds,
    ) : this(SessionHold(scope, renewInterval, newIdempotencyKey, openTimeout))

    /** The session held now, or null between channels, after [release] and before the server is configured. */
    val current: StateFlow<PlaybackStream?> = hold.current.asStateFlow()

    /** Streams the server replaced under a held session, which the player must reopen. */
    val replaced: SharedFlow<PlaybackStream> = hold.replaced.asSharedFlow()

    /** Points at a server, releasing whatever the previous one held; null until Settings names a valid server. */
    suspend fun configure(api: SofaKingApi?): Unit = withContext(NonCancellable) {
        // One lock for both, so no tune queued behind the release opens a session on the old server.
        hold.mutex.withLock {
            hold.resumable = null
            val previous = hold.api
            val held = hold.current.value
            if (held != null && previous != null) hold.releaseHeld(previous, held)
            hold.api = api
        }
    }

    /**
     * Releases the held session. It can be reopened with [reopen] unless [forget] is set. With [itemId], only that
     * item's session is released, so a page that leaves after the next one opened leaves the next one's alone.
     */
    suspend fun release(forget: Boolean = false, itemId: String? = null): Unit = withContext(NonCancellable) {
        hold.mutex.withLock {
            val held = hold.current.value
            if (itemId != null && held?.itemId != itemId) return@withLock
            if (forget) {
                hold.resumable = null
            } else if (held != null) {
                hold.resumable = when {
                    held.isMultiview -> Resumable.Multiview(held.tiles, held.audioTile)

                    else -> hold.items[held.sessionId]?.let { Resumable.Item(it) }
                        ?: Resumable.Live(held.channelId, held.intent)
                }
            }
            val api = hold.api
            if (held != null && api != null) hold.releaseHeld(api, held)
        }
    }

    /**
     * [release] on this instance's own scope, for callers that cannot suspend or whose scope may already be canceled.
     */
    fun releaseAsync(forget: Boolean = false, itemId: String? = null) {
        hold.scope.launch { release(forget, itemId) }
    }

    /**
     * The DELETE that frees the held session, for a page being closed: it cannot wait for [release], whose request
     * would never leave, so it sends this itself as the page goes. Null when nothing is held. Nothing here changes; a
     * page the browser keeps and shows again finds its session gone and reopens it as after any dropped session.
     */
    fun exitRelease(): SessionRelease? {
        val api = hold.api ?: return null
        val held = hold.current.value ?: return null
        return api.releaseRequest(
            when {
                held.isMultiview -> multiviewPath(held.sessionId)
                held.itemId != null -> vodPath(held.sessionId)
                else -> sessionPath(held.sessionId)
            },
        )
    }

    /**
     * Opens a fresh session for what was released last: the channel with its intent, or the library item where
     * the player last was in it. Null when there is nothing to return to.
     */
    suspend fun reopen(): PlaybackStream? = when (val last = hold.mutex.withLock { hold.resumable }) {
        null -> null
        is Resumable.Live -> tune(last.channelId, last.intent)
        is Resumable.Item -> playing.openItem(last.request, hold.mutex.withLock { hold.itemPositionMs })
        is Resumable.Multiview -> multiview(last.channelIds, last.audio)
    }
}

/** [LiveSessions] on the session [hold] keeps. */
internal class LiveTuning(private val hold: SessionHold) : LiveSessions {
    override suspend fun tune(channelId: String, intent: PlaybackIntent): PlaybackStream = with(hold) {
        mutex.withLock {
            val api = api ?: throw IOException("No server configured")
            // A tune supersedes what an earlier stop left to reopen, even when this open fails.
            resumable = null
            val held = current.value
            if (held != null && held.channelId == channelId && !held.isMultiview) {
                val reread = reread(api, held)
                if (reread != null) {
                    return@withLock if (reread.intent == intent) {
                        reread
                    } else {
                        publish(api.updateSession(reread.sessionId, intent.wire), intent)
                    }
                }
            } else if (held != null) {
                releaseHeld(api, held)
            }
            open(api, LiveSessionKind(channelId, intent, newIdempotencyKey))
        }
    }

    override suspend fun adBreak(aheadMs: Long): AdBreakNow? {
        val api = hold.api ?: return null
        val held = hold.current.value?.takeIf { it.itemId == null && !it.seekable && !it.isMultiview } ?: return null
        val state = api.adBreak(held.sessionId, aheadMs)
        return if (state.inBreak) AdBreakNow(state.remainingMs) else null
    }

    override suspend fun health(): StreamHealth? {
        val api = hold.api ?: return null
        val held = hold.current.value?.takeIf { it.itemId == null && !it.seekable && !it.isMultiview } ?: return null
        val health = api.session(held.sessionId).health ?: return null
        return StreamHealth(health.bitrateKbps, health.stalls, health.sourceSwitches)
    }

    override suspend fun setIntent(channelId: String, intent: PlaybackIntent) {
        hold.mutex.withLock {
            val api = hold.api ?: return
            val held = hold.current.value
                ?.takeIf { it.channelId == channelId && it.intent != intent && !it.isMultiview } ?: return
            hold.publish(api.updateSession(held.sessionId, intent.wire), intent)
        }
    }
}

/** [ItemSessions] on the session [hold] keeps. */
internal class ItemPlaying(private val hold: SessionHold) : ItemSessions {
    override suspend fun play(
        itemId: String,
        profile: PlaybackProfile,
        startMs: Long,
        audioIndex: Int?,
        subtitleIndex: Int?,
    ): PlaybackStream = openItem(ItemRequest(itemId, profile, audioIndex, subtitleIndex), startMs)

    override suspend fun playRecording(recordingId: String, profile: PlaybackProfile, startMs: Long): PlaybackStream =
        openItem(ItemRequest(recordingId, profile, audioIndex = null, subtitleIndex = null, recording = true), startMs)

    /** Opens [request] from [startMs] in the item, releasing the held session first. */
    suspend fun openItem(request: ItemRequest, startMs: Long): PlaybackStream = with(hold) {
        mutex.withLock {
            val api = api ?: throw IOException("No server configured")
            resumable = null
            current.value?.let { releaseHeld(api, it) }
            itemPositionMs = startMs.coerceAtLeast(0)
            open(api, ItemSessionKind(request, itemPositionMs))
        }
    }

    override suspend fun restartAt(positionMs: Long): PlaybackStream? = with(hold) {
        mutex.withLock {
            val api = api ?: return null
            val held = current.value?.takeIf { it.itemId != null } ?: return null
            val request = items[held.sessionId] ?: return null
            val start = positionMs.coerceAtLeast(0)
            itemPositionMs = start
            if (held.mode == Wire.VodMode.DIRECT) return held
            publishItem(api, api.updateVod(held.sessionId, start), request, start)
        }
    }

    override fun reportPosition(positionMs: Long) {
        if (hold.current.value?.itemId != null) hold.itemPositionMs = positionMs.coerceAtLeast(0)
    }
}

/** [MultiviewSessions] on the session [hold] keeps. */
internal class MultiviewPlaying(private val hold: SessionHold) : MultiviewSessions {
    override suspend fun multiview(channelIds: List<String>, audio: Int): PlaybackStream = with(hold) {
        mutex.withLock {
            val api = api ?: throw IOException("No server configured")
            resumable = null
            current.value?.let { releaseHeld(api, it) }
            open(api, MultiviewSessionKind(channelIds, audio))
        }
    }

    override suspend fun moveAudio(tile: Int): PlaybackStream? = with(hold) {
        mutex.withLock {
            val api = api ?: return null
            val held = current.value?.takeIf { it.isMultiview } ?: return null
            if (held.audioTile == tile) return held
            multiviewStream(api, api.updateMultiview(held.sessionId, tile)).also { current.value = it }
        }
    }
}
