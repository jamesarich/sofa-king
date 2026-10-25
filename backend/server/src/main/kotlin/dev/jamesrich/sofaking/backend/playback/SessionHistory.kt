package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.api.HistoryEntry
import dev.jamesrich.sofaking.backend.api.HistoryQuery
import dev.jamesrich.sofaking.backend.api.SessionStart
import dev.jamesrich.sofaking.backend.events.EventType
import dev.jamesrich.sofaking.backend.events.ServerEvents
import dev.jamesrich.sofaking.backend.runCatchingCancellable
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.addHistory
import dev.jamesrich.sofaking.backend.store.history
import dev.jamesrich.sofaking.backend.store.pruneHistory
import dev.jamesrich.sofaking.backend.store.wholeSeconds
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

private val log = LoggerFactory.getLogger(SessionHistory::class.java)
private val eventJson = Json { encodeDefaults = true }

/** The kinds of session the history keeps. */
object HistoryKind {
    const val LIVE = "live"
    const val VOD = "vod"
}

/** How a session ended. */
object HistoryReason {
    /** The player released it. */
    const val RELEASED = "released"

    /** Its lease ran out with no renewal and no media request. */
    const val EXPIRED = "expired"

    /** Another session took its slot: a preview or an idle player's, when the slots ran out. */
    const val EVICTED = "evicted"

    /** The server stopped. */
    const val STOPPED = "stopped"
}

/**
 * Who opened a session: the player's [address] and, when it presented a paired device's token, that device's
 * [clientId].
 */
data class PlaybackClient(val address: String? = null, val clientId: String? = null)

/** The days of history kept by default, `SOFA_HISTORY_DAYS`. */
const val DEFAULT_HISTORY_DAYS = 90

/**
 * The playback history: each live and VOD session that ends becomes a row, kept for [retention], and each start and
 * end is published to [events]. The sessions report from under their own locks, so [started] and [ended] never
 * suspend; the writer [start] runs saves the rows, and every read saves what is waiting first.
 */
class SessionHistory(
    private val store: BackendStore,
    private val events: ServerEvents,
    private val clock: Clock,
    private val retention: Duration = Duration.ofDays(DEFAULT_HISTORY_DAYS.toLong()),
) {
    private val pending = ConcurrentLinkedQueue<HistoryEntry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val writing = Mutex()

    fun started(session: SessionStart) {
        events.publish(EventType.SESSION_STARTED, eventJson.encodeToJsonElement(session).jsonObject)
    }

    /** [session] ended for [reason]; [mode] is how it played at the end, and [bytes] what the relay sent. */
    fun ended(session: SessionStart, reason: String, mode: String = session.mode, bytes: Long? = null) {
        val entry = HistoryEntry(
            session.id, session.kind, session.clientId, session.address, session.channelId, session.itemId,
            session.recordingId, session.providerId, mode, reason, Instant.parse(session.startedAt).wholeSeconds(),
            clock.instant().wholeSeconds(), bytes,
        )
        pending += entry
        wake.trySend(Unit)
        events.publish(EventType.SESSION_ENDED, eventJson.encodeToJsonElement(entry).jsonObject)
    }

    /** Saves the rows waiting; a failed save keeps them for the next. */
    suspend fun flush() = writing.withLock {
        val batch = generateSequence { pending.poll() }.toList()
        runCatchingCancellable { store.addHistory(batch) }.onFailure { pending.addAll(batch) }.getOrThrow()
    }

    /** The ended sessions [query] matches, the newest first. */
    suspend fun list(query: HistoryQuery): List<HistoryEntry> {
        flush()
        return store.history(query)
    }

    /** Deletes what ended more than [retention] ago. */
    suspend fun prune(): Int = store.pruneHistory(clock.instant().minus(retention))

    /** Saves the rows as sessions end, until [scope] is canceled. */
    fun start(scope: CoroutineScope): Job = scope.launch(CoroutineName("session-history")) {
        while (isActive) {
            wake.receive()
            runCatchingCancellable { flush() }
                .onFailure { log.warn("session history save failed class={}", it.javaClass.simpleName) }
        }
    }
}
