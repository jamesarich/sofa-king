package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.CatalogService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.eligible
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/** The requests that read a session's source, the relay's and streams served outside it, and what their source does. */
class SessionMedia internal constructor(
    private val table: SessionTable,
    private val catalog: CatalogService,
    private val store: BackendStore,
    private val clock: Clock,
    private val sources: SourceChoice,
) {
    /** The resource [token] of session [id] at [epoch], for the media request [job] from [client]. */
    suspend fun acquire(id: String, epoch: Int, token: String, job: Job, client: String? = null): MediaResource =
        table.mutex.withLock {
            val session = table.active(id)
            if (client != null) session.client = client
            if (epoch != session.epoch) {
                throw ApiFailure(
                    HttpStatusCode.Gone,
                    Wire.ErrorCode.STREAM_CHANGED,
                    "Fetch the current session descriptor",
                    true,
                )
            }
            if (catalog.offering(session.offering.id) == null ||
                store.health(session.offering.id)?.let(::eligible) != true
            ) {
                session.cancel()
                throw ApiFailure(
                    HttpStatusCode.ServiceUnavailable,
                    "SOURCE_UNAVAILABLE",
                    "Source is no longer eligible",
                    true,
                )
            }
            val url =
                session.resources[token]
                    ?: throw ApiFailure(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "Unknown media resource")
            val root = session.offering.protocol == "mpegts" && url == session.offering.url
            val packaged = root && session.packaged
            val shared = root && !packaged
            // A player reconnecting to a live stream replaces its previous request rather than adding to it.
            if (shared) session.cancel()
            if (session.requests.size >= MAX_REQUESTS) {
                throw ApiFailure(
                    HttpStatusCode.TooManyRequests,
                    Wire.ErrorCode.CAPACITY_EXHAUSTED,
                    "Too many media requests",
                    true,
                )
            }
            session.requests += job
            if (session.external) session.expires = clock.instant().plus(SessionManager.EXTERNAL_IDLE)
            MediaResource(
                session.offering,
                url,
                shared,
                packaged,
                session.stats,
                session.ads,
                session.playlists,
                session.stalls,
            )
        }

    /** The media request [job] of session [id] ended; an external session's idle lease starts again. */
    suspend fun finished(id: String, job: Job) = table.mutex.withLock {
        table.sessions[id]?.let { session ->
            session.requests.remove(job)
            if (session.external) session.expires = clock.instant().plus(SessionManager.EXTERNAL_IDLE)
        }
        Unit
    }

    /** The path through this server of [url], a resource a manifest of session [id] at [epoch] names. */
    suspend fun resourcePath(id: String, epoch: Int, url: String): String = table.mutex.withLock {
        val session = table.active(id)
        if (session.epoch != epoch) {
            throw ApiFailure(HttpStatusCode.Gone, Wire.ErrorCode.STREAM_CHANGED, "Fetch current descriptor", true)
        }
        "/media/sessions/$id/$epoch/${session.resource(url)}"
    }

    /** Registers a request that reads the source outside the relay, so the session lives as long as [job] does. */
    suspend fun attach(id: String, job: Job) = table.mutex.withLock {
        table.active(id).requests += job
        Unit
    }

    suspend fun offeringOf(id: String): Offering = table.mutex.withLock { table.active(id).offering }

    /** The counter of bytes sent to the session's player, for a stream served outside the relay. */
    suspend fun bytesOf(id: String): AtomicLong = table.mutex.withLock { table.active(id).bytes }

    /**
     * What a media request the session let go of answers: what the player's next request would, if the session is gone.
     */
    suspend fun letGo(id: String): ApiFailure = table.mutex.withLock {
        if (id in table.sessions) {
            ApiFailure(HttpStatusCode.Gone, Wire.ErrorCode.STREAM_CHANGED, "Fetch the current session descriptor", true)
        } else {
            ApiFailure(HttpStatusCode.Gone, Wire.ErrorCode.SESSION_EXPIRED, "Session was released or expired")
        }
    }

    /**
     * The session's source failed to resolve, so the session moves on to the channel's next eligible source and
     * true comes back: the relay answers 410 and the player re-reads the descriptor. False when no other source is
     * left. A source's health only changes with the prober, so without this one failing provider would refuse the
     * channel until the next probe even with healthy sources behind it.
     */
    suspend fun sourceFailed(id: String, epoch: Int, job: Job): Boolean = table.mutex.withLock {
        val session = table.active(id)
        if (session.epoch != epoch) return@withLock true
        session.failed += session.offering.id
        val candidates = catalog.offeringsFor(session.request.channelId)?.filter { it.id !in session.failed }
        val next = candidates?.let { sources.choose(it, store.allHealth()) } ?: return@withLock false
        session.restart(except = job)
        session.offering = next
        session.stats.switches.incrementAndGet()
        true
    }

    /**
     * The first fetch found [protocol] instead of the descriptor's; the source keeps it,
     * across restarts, for later sessions.
     */
    suspend fun protocolObserved(id: String, epoch: Int, protocol: String, job: Job) {
        val offeringId = table.mutex.withLock {
            val session = table.active(id)
            if (session.epoch != epoch) return
            session.restart(except = job)
            session.offering = session.offering.copy(protocol = protocol)
            session.offering.id
        }
        catalog.feeds.recordProtocol(offeringId, protocol)
    }

    /**
     * Moves session [id] to the channel's next eligible source, passing over the one it plays and those it was
     * switched away from (all of them again once none is left), and to a new epoch, so the app re-reads the session
     * and another player's next media request answers 410. Refused with 409 when the channel has no other source.
     */
    suspend fun switchSource(id: String): PlaybackSession = table.mutex.withLock {
        val session = table.active(id)
        val current = session.offering.id
        val candidates = catalog.offeringsFor(session.request.channelId).orEmpty()
            .filter { it.id != current && it.id !in session.failed }
        val states = store.allHealth()
        val next = sources.choose(candidates.filter { it.id !in session.switchedFrom }, states)
            ?: sources.choose(candidates, states)?.also { session.switchedFrom.clear() }
            ?: throw ApiFailure(HttpStatusCode.Conflict, "NO_OTHER_SOURCE", "The channel has no other eligible source")
        session.switchedFrom += current
        session.restart()
        session.offering = next
        session.stats.switches.incrementAndGet()
        session.descriptor(clock.instant())
    }

    /**
     * The source of session [id] stalled or fell below a quality floor ([reason]), so the session moves to the
     * channel's next eligible source it has not played, cancelling its requests but [except], and the source it left
     * comes back. Null when no such source is left, and the session stays. At an [epoch] other than the session's, it
     * has moved on already, and its current source comes back.
     */
    suspend fun failover(id: String, epoch: Int?, except: Job?, reason: String): Offering? = table.mutex.withLock {
        val session = table.active(id)
        if (epoch != null && session.epoch != epoch) return@withLock session.offering
        val current = session.offering
        val candidates = catalog.offeringsFor(session.request.channelId).orEmpty().filter {
            it.id != current.id && it.id !in session.failed && it.id !in session.switchedFrom
        }
        val next = sources.choose(candidates, store.allHealth()) ?: return@withLock null
        session.switchedFrom += current.id
        session.restart(except = except)
        session.offering = next
        session.stats.switches.incrementAndGet()
        log.info(
            "playback failover session={} channel={} reason={} from={} to={}",
            id,
            session.request.channelId,
            reason,
            current.providerId,
            next.providerId,
        )
        next
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(SessionMedia::class.java)

        /** How many media requests one session may have in flight. */
        const val MAX_REQUESTS = 12
    }
}
