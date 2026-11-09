package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import java.time.Clock

internal val learnedLog = LoggerFactory.getLogger(AdClock::class.java)

private const val REMEMBERED_PREEMPTED = 256

/** The open sessions, which [mutex] guards, and what ends them. */
internal class SessionTable(private val clock: Clock, capacity: Int) {
    val mutex = Mutex()
    val sessions = linkedMapOf<String, LiveSession>()

    /** How many sessions may be open at once. */
    @Volatile var capacity: Int = capacity

    /** Gets what each app session that played found on its channel, as it ends. */
    @Volatile var onCensus: (String, String, AdCensus) -> Unit = { _, _, _ -> }

    /** Hears that a recording took the slot of an app session: the channel it was watching, and the recording's. */
    @Volatile var onPreempted: (String, String) -> Unit = { _, _ -> }

    /** Where each session's start and end is recorded; none until the runtime sets it. */
    @Volatile var history: SessionHistory? = null

    /** Adds [session] to the table and reports its start. */
    fun add(session: LiveSession) {
        sessions[session.id] = session
        history?.started(session.start())
    }

    /** The sessions a recording took the slot of, with what their next request answers; the oldest are forgotten. */
    private val preempted = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > REMEMBERED_PREEMPTED
    }

    /** The open session [id], once the expired ones are gone. */
    fun active(id: String): LiveSession {
        reap()
        return sessions[id] ?: throw gone(id)
    }

    /** What a request for the ended session [id] answers: that a recording took its slot, or that it expired. */
    fun gone(id: String): ApiFailure = preempted[id]?.let {
        ApiFailure(HttpStatusCode.Gone, Wire.ErrorCode.SESSION_PREEMPTED, it)
    } ?: ApiFailure(HttpStatusCode.Gone, Wire.ErrorCode.SESSION_EXPIRED, "Session was released or expired")

    /** A player's in-flight request holds its external session; the idle lease only starts once that request ends. */
    fun reap() {
        val now = clock.instant()
        sessions.values.filter {
            it.expires <= now && !(it.external && it.busy)
        }.forEach { end(it, HistoryReason.EXPIRED) }
    }

    /**
     * Previews are the surfing state and idle player sessions are leftovers of a zap; either yields its slot before
     * the request is refused. A playing HLS player is idle between segments too, so the stalest one (least recent
     * media request, the earliest expiry) goes, never the one still fetching.
     *
     * A request made for a recording passes [preemptFor], the recording's channel, and [recording], its name: with
     * nothing else to give, the least recently active app session that is watching yields its slot too (one with a
     * media request in flight counts as active now), [onPreempted] hears of it, and the session's next request
     * answers `SESSION_PREEMPTED`. Recordings, taps and other players outside the app are never taken.
     */
    fun makeRoom(preemptFor: String? = null, recording: String? = null) {
        if (sessions.size < capacity) return
        val now = clock.instant()
        val victim = sessions.values.firstOrNull { it.intent == Wire.Intent.PREVIEW }
            ?: sessions.values.filter { it.external && !it.busy && !it.held }.minByOrNull { it.expires }
            ?: preemptFor?.let { channel ->
                sessions.values.filter { !it.external && it.intent == Wire.Intent.WATCH }
                    .minByOrNull { if (it.busy) now else it.lastActive }
                    ?.also {
                        preempted[it.id] = "A recording of ${recording ?: "another channel"} took this channel's slot"
                        onPreempted(it.request.channelId, channel)
                    }
            }
            ?: throw ApiFailure(
                HttpStatusCode.TooManyRequests,
                Wire.ErrorCode.CAPACITY_EXHAUSTED,
                "The server is at its playback limit; try again shortly",
                true,
            )
        end(victim, HistoryReason.EVICTED)
    }

    /**
     * Ends [session] for [reason]: its requests stop, it leaves the table and the history, and what it found is logged
     * as `ADCENSUS`.
     */
    fun end(session: LiveSession, reason: String) {
        session.cancel()
        if (sessions.remove(session.id) == null) return
        history?.ended(session.start(), reason, session.mode(), session.bytes.get())
        val census = session.ads?.census()?.takeIf { it.playedMs > 0 } ?: return
        learnedLog.info(
            "ADCENSUS channel={} provider={} playedMs={} marked={} breaks={} learned={} joinedInBreak={} session={}",
            session.request.channelId,
            session.offering.providerId,
            census.playedMs,
            census.marked,
            census.breaks,
            census.learned,
            census.joinedInBreak,
            session.id,
        )
        onCensus(session.request.channelId, session.offering.providerId, census)
    }
}
