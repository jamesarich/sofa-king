package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.contract.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.HealthState
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.store.BackendStore
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val RESOURCE_SUFFIXES = setOf("m3u8", "ts", "m4s", "mp4", "aac", "vtt", "webvtt", "key", "bin")
private val INTENTS = setOf(Wire.Intent.PREVIEW, Wire.Intent.WATCH)

/** [external] sessions belong to players that never renew: the lease runs from their last media request. */
internal class LiveSession(
    val id: String, val request: OpenPlayback, var offering: Offering, var expires: Instant, val external: Boolean = false,
    /** When the session opened, for the portal's Watching now. */
    val startedAt: Instant = expires,
    /** The address of the player that opened it, or of its last media request. */
    @Volatile var client: String? = null,
) {
    var epoch = 1
    var intent = request.intent
    /** Bytes relayed to the player over every media request, for the portal's Watching now. */
    val bytes = AtomicLong()
    val busy: Boolean get() = requests.any { it.isActive }
    /** Token to URL for every mapped resource; playlists are pinned, segments and keys share a 4,096-entry LRU. */
    val resources = ResourceMap()
    val requests = mutableSetOf<Job>()
    fun resource(url: String, suffix: String? = null): String {
        resources.tokenFor(url)?.let { return it }
        val extension = java.net.URI(url).path.substringAfterLast('.', "").lowercase()
        val chosen = suffix ?: if (extension in RESOURCE_SUFFIXES) extension else "ts"
        return "${UUID.randomUUID()}.$chosen".also { resources.put(it, url, pinned = chosen == "m3u8" || url == offering.url) }
    }
    /** The root token's suffix and MIME follow the offering's protocol, never the upstream path. */
    fun descriptor(): PlaybackSession {
        val hls = offering.protocol == "hls"
        val token = resource(offering.url, if (hls) "m3u8" else "ts")
        return PlaybackSession(id, request.channelId, intent, expires.toString(), PlaybackDescriptor(offering.protocol,
            if (hls) "application/vnd.apple.mpegurl" else "video/mp2t", "/media/sessions/$id/$epoch/$token", epoch))
    }
    fun cancel(except: Job? = null) { requests.filter { it != except }.forEach { it.cancel() }; requests.retainAll { it == except } }
}
/** O(1) in both directions. Pinned entries (root and playlists) never leave; the rest is a bounded LRU of segments and keys. */
internal class ResourceMap(private val segmentLimit: Int = 4096) {
    private val pinned = HashMap<String, String>()
    private val segments = object : java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            if (size <= segmentLimit) return false
            tokens.remove(eldest.value)
            return true
        }
    }
    private val tokens = HashMap<String, String>()
    val size: Int get() = pinned.size + segments.size
    operator fun get(token: String): String? = pinned[token] ?: segments[token]
    fun tokenFor(url: String): String? = tokens[url]?.also { token -> segments[token] }
    fun put(token: String, url: String, pinned: Boolean) {
        tokens[url] = token
        if (pinned) this.pinned[token] = url else segments[token] = url
    }
    fun clear() { pinned.clear(); segments.clear(); tokens.clear() }
}

/**
 * [shared] marks the live MPEG-TS root, which one upstream connection fans out to every session on the source;
 * [bytes] counts what the relay sends the player on this session.
 */
data class MediaResource(val offering: Offering, val url: String, val shared: Boolean = false, val bytes: AtomicLong = AtomicLong())

class SessionManager(private val catalog: CatalogService, private val store: BackendStore, private val clock: Clock, capacity: Int = 4) {
    /** How many sessions may be open at once; the runtime settings change it without a restart. */
    @Volatile var capacity: Int = capacity
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, LiveSession>()
    private val keys = linkedMapOf<String, Pair<OpenPlayback, String>>()
    /** [client] is the address of the player opening the session, shown to the operator. */
    suspend fun open(request: OpenPlayback, client: String? = null): PlaybackSession = mutex.withLock {
        reap()
        if (request.intent !in INTENTS || request.idempotencyKey.length !in 1..128)
            throw ApiFailure(400, "INVALID_REQUEST", "Invalid playback intent or idempotency key")
        keys[request.idempotencyKey]?.let { (original, id) ->
            if (original != request) throw ApiFailure(409, "IDEMPOTENCY_CONFLICT", "Key already used for another request")
            return@withLock sessions[id]?.descriptor() ?: throw ApiFailure(410, Wire.ErrorCode.SESSION_EXPIRED, "Session was released or expired")
        }
        makeRoom()
        val offering = select(request.channelId)
        val now = clock.instant()
        val session = LiveSession(UUID.randomUUID().toString(), request, offering, now.plus(lease(request.intent)), startedAt = now, client = client)
        sessions[session.id] = session
        keys[request.idempotencyKey] = request to session.id
        while (keys.size > 4096) keys.remove(keys.keys.first())
        session.descriptor()
    }
    /** A watch session for a player that sends no API calls; it lives while media requests keep arriving. */
    suspend fun openExternal(channelId: String, client: String? = null): PlaybackSession = mutex.withLock {
        reap()
        makeRoom()
        val id = UUID.randomUUID().toString()
        val now = clock.instant()
        val session = LiveSession(id, OpenPlayback(channelId, Wire.Intent.WATCH, "external:$id"), select(channelId), now.plus(EXTERNAL_IDLE), external = true, startedAt = now, client = client)
        sessions[id] = session
        session.descriptor()
    }
    /** An external HLS session already playing [channelId]; a second player shares it. A TS root replaces its reader, so it is never shared. */
    suspend fun externalFor(channelId: String): PlaybackSession? = mutex.withLock {
        reap()
        sessions.values.firstOrNull { it.external && it.request.channelId == channelId && it.offering.protocol == "hls" }
            ?.also { it.expires = clock.instant().plus(EXTERNAL_IDLE) }?.descriptor()
    }
    /** Registers a request that reads the source outside the relay, so the session lives as long as [job] does. */
    suspend fun attach(id: String, job: Job) = mutex.withLock { active(id).requests += job; Unit }
    suspend fun offeringOf(id: String): Offering = mutex.withLock { active(id).offering }
    /** The counter of bytes sent to the session's player, for a stream served outside the relay. */
    suspend fun bytesOf(id: String): AtomicLong = mutex.withLock { active(id).bytes }
    suspend fun count(): Int = mutex.withLock { reap(); sessions.size }
    suspend fun snapshot(): List<ActiveSession> = mutex.withLock {
        reap()
        sessions.values.map {
            ActiveSession(it.id, it.request.channelId, it.intent, it.external, it.busy, it.offering.providerId, it.expires.toString(), it.startedAt.toString(), it.client, it.bytes.get())
        }
    }
    /** The source each channel would play now, for players that need the protocol before opening. */
    suspend fun preferredOfferings(channelIds: Collection<String>): Map<String, Offering> {
        val states = store.allHealth()
        return channelIds.mapNotNull { id -> catalog.offeringsFor(id)?.let { candidates -> choose(candidates, states) }?.let { id to it } }.toMap()
    }
    /**
     * Previews are the surfing state and idle player sessions are leftovers of a zap; either yields its slot before
     * the request is refused. A playing HLS player is idle between segments too, so the stalest one (least recent
     * media request, the earliest expiry) goes, never the one still fetching.
     */
    private fun makeRoom() {
        if (sessions.size < capacity) return
        val victim = sessions.values.firstOrNull { it.intent == Wire.Intent.PREVIEW }
            ?: sessions.values.filter { it.external && !it.busy }.minByOrNull { it.expires }
            ?: throw ApiFailure(429, Wire.ErrorCode.CAPACITY_EXHAUSTED, "Playback capacity exhausted", true)
        victim.cancel()
        sessions.remove(victim.id)
    }
    suspend fun get(id: String): PlaybackSession = mutex.withLock {
        val session = active(id)
        val health = store.health(session.offering.id)
        val refreshed = catalog.offering(session.offering.id)
        if (refreshed == null || health == null || !eligible(health)) {
            session.cancel(); session.epoch++; session.resources.clear()
            session.offering = select(session.request.channelId)
        }
        if (refreshed != null && session.offering.id == refreshed.id && session.offering.url != refreshed.url) {
            session.cancel(); session.offering = refreshed; session.epoch++; session.resources.clear()
        }
        session.descriptor()
    }
    suspend fun update(id: String, update: UpdatePlayback): PlaybackSession = mutex.withLock {
        val session = active(id)
        if (update.intent != null && update.intent !in INTENTS) throw ApiFailure(400, "INVALID_REQUEST", "Invalid playback intent")
        update.intent?.let { session.intent = it }
        session.expires = clock.instant().plus(lease(session.intent))
        session.descriptor()
    }
    /** Leases outlast the app's 15-second renewal several times over; abandoned previews free their slot sooner. */
    private fun lease(intent: String): Duration = if (intent == Wire.Intent.WATCH) Duration.ofSeconds(90) else Duration.ofSeconds(45)
    /** Ends the session; true when it was open. */
    suspend fun release(id: String): Boolean = mutex.withLock { sessions.remove(id)?.cancel() != null }
    suspend fun healthChanged(offeringId: String) = mutex.withLock {
        if (store.health(offeringId)?.let(::eligible) != true) {
            sessions.values.filter { it.offering.id == offeringId }.forEach {
                it.cancel(); it.epoch++; it.resources.clear()
            }
        }
    }
    /** The first fetch found [protocol] instead of the descriptor's; the source keeps it, across restarts, for later sessions. */
    suspend fun protocolObserved(id: String, epoch: Int, protocol: String, job: Job) {
        val offeringId = mutex.withLock {
            val session = active(id)
            if (session.epoch != epoch) return
            session.cancel(except = job)
            session.offering = session.offering.copy(protocol = protocol)
            session.epoch++
            session.resources.clear()
            session.offering.id
        }
        catalog.feeds.recordProtocol(offeringId, protocol)
    }
    suspend fun expire() = mutex.withLock { reap() }
    suspend fun close() = mutex.withLock { sessions.values.forEach { it.cancel() }; sessions.clear() }
    suspend fun acquire(id: String, epoch: Int, token: String, job: Job, client: String? = null): MediaResource = mutex.withLock {
        val session = active(id)
        if (client != null) session.client = client
        if (epoch != session.epoch) throw ApiFailure(410, Wire.ErrorCode.STREAM_CHANGED, "Fetch the current session descriptor", true)
        if (catalog.offering(session.offering.id) == null || store.health(session.offering.id)?.let(::eligible) != true) {
            session.cancel()
            throw ApiFailure(503, "SOURCE_UNAVAILABLE", "Source is no longer eligible", true)
        }
        val url = session.resources[token] ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND", "Unknown media resource")
        val shared = session.offering.protocol == "mpegts" && url == session.offering.url
        // A player reconnecting to a live stream replaces its previous request rather than adding to it.
        if (shared) session.cancel()
        if (session.requests.size >= 12) throw ApiFailure(429, Wire.ErrorCode.CAPACITY_EXHAUSTED, "Too many media requests", true)
        session.requests += job
        if (session.external) session.expires = clock.instant().plus(EXTERNAL_IDLE)
        MediaResource(session.offering, url, shared, session.bytes)
    }
    suspend fun finished(id: String, job: Job) = mutex.withLock {
        sessions[id]?.let { session ->
            session.requests.remove(job)
            if (session.external) session.expires = clock.instant().plus(EXTERNAL_IDLE)
        }
        Unit
    }
    suspend fun resourcePath(id: String, epoch: Int, url: String): String = mutex.withLock {
        val session = active(id)
        if (session.epoch != epoch) throw ApiFailure(410, Wire.ErrorCode.STREAM_CHANGED, "Fetch current descriptor", true)
        "/media/sessions/$id/$epoch/${session.resource(url)}"
    }
    /** Lineup order within each health rank: a healthy second choice beats an unverified first choice. */
    private suspend fun select(channelId: String): Offering {
        val candidates = catalog.offeringsFor(channelId) ?: throw ApiFailure(404, Wire.ErrorCode.CHANNEL_NOT_FOUND, "Unknown channel")
        return choose(candidates, store.allHealth()) ?: throw ApiFailure(503, "SOURCE_UNAVAILABLE", "No eligible source", true)
    }
    private fun choose(candidates: List<Offering>, states: Map<String, HealthState>): Offering? =
        candidates.sortedBy { selectionRank(states[it.id]?.status) }.firstOrNull { states[it.id]?.let(::eligible) == true }
    private fun active(id: String): LiveSession {
        reap()
        return sessions[id] ?: throw ApiFailure(410, Wire.ErrorCode.SESSION_EXPIRED, "Session was released or expired")
    }
    /** A player's in-flight request holds its external session; the idle lease only starts once that request ends. */
    private fun reap() {
        val now = clock.instant()
        val expired = sessions.values.filter { it.expires <= now && !(it.external && it.busy) }
        expired.forEach { it.cancel(); sessions.remove(it.id) }
    }
    companion object {
        /** How long a player may go without a media request before its session is released. */
        val EXTERNAL_IDLE: Duration = Duration.ofSeconds(60)
    }
}
/** Healthy first, then unverified, then suspect; anything else is never selected. */
internal fun selectionRank(status: HealthStatus?): Int =
    when (status) {
        HealthStatus.HEALTHY -> 0
        HealthStatus.UNVERIFIED -> 1
        HealthStatus.SUSPECT -> 2
        else -> 3
    }
