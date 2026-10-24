package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.catalog.ApiFailure
import dev.jamesrich.sofaking.backend.catalog.CatalogService
import dev.jamesrich.sofaking.backend.catalog.Offering
import dev.jamesrich.sofaking.backend.catalog.eligible
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.allHealth
import dev.jamesrich.sofaking.backend.store.health
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
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
            val shared = session.offering.protocol == "mpegts" && url == session.offering.url
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
            MediaResource(session.offering, url, shared, session.bytes, session.ads, session.playlists)
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

    private companion object {
        /** How many media requests one session may have in flight. */
        const val MAX_REQUESTS = 12
    }
}
