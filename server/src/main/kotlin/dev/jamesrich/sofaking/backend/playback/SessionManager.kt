package dev.jamesrich.sofaking.backend.playback

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.health.HealthStatus
import dev.jamesrich.sofaking.backend.store.BackendStore
import java.time.*
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LiveSession(val id: String, val request: OpenPlayback, var offering: Offering, var expires: Instant) {
    var epoch = 1
    var intent = request.intent
    /** Token to URL for every mapped resource; playlists are pinned, segments and keys share a 4,096-entry LRU. */
    val resources = ResourceMap()
    val requests = mutableSetOf<Job>()
    fun resource(url: String, suffix: String? = null): String {
        resources.tokenFor(url)?.let { return it }
        val extension = java.net.URI(url).path.substringAfterLast('.', "").lowercase()
        val chosen = suffix ?: if (extension in setOf("m3u8", "ts", "m4s", "mp4", "aac", "vtt", "webvtt", "key", "bin")) extension else "ts"
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

/** [shared] marks the live MPEG-TS root, which one upstream connection fans out to every session on the source. */
data class MediaResource(val offering: Offering, val url: String, val shared: Boolean = false)

class SessionManager(private val feeds: FeedService, private val store: BackendStore, private val clock: Clock, private val capacity: Int = 4) {
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, LiveSession>()
    private val keys = linkedMapOf<String, Pair<OpenPlayback, String>>()
    suspend fun open(request: OpenPlayback): PlaybackSession = mutex.withLock {
        reap()
        if (request.intent !in setOf("preview", "watch") || request.idempotencyKey.length !in 1..128)
            throw ApiFailure(400, "INVALID_REQUEST", "Invalid playback intent or idempotency key")
        keys[request.idempotencyKey]?.let { (original, id) ->
            if (original != request) throw ApiFailure(409, "IDEMPOTENCY_CONFLICT", "Key already used for another request")
            return@withLock sessions[id]?.descriptor() ?: throw ApiFailure(410, "SESSION_EXPIRED", "Session was released or expired")
        }
        if (sessions.size >= capacity) {
            // Previews are the surfing state; the oldest one yields its slot rather than refusing the new request.
            val preview = sessions.values.firstOrNull { it.intent == "preview" }
                ?: throw ApiFailure(429, "CAPACITY_EXHAUSTED", "Playback capacity exhausted", true)
            preview.cancel()
            sessions.remove(preview.id)
        }
        val offering = select(request.channelId)
        val session = LiveSession(UUID.randomUUID().toString(), request, offering, clock.instant().plus(lease(request.intent)))
        sessions[session.id] = session
        keys[request.idempotencyKey] = request to session.id
        while (keys.size > 4096) keys.remove(keys.keys.first())
        session.descriptor()
    }
    suspend fun get(id: String): PlaybackSession = mutex.withLock {
        val session = active(id)
        val health = store.health(session.offering.id)
        val refreshed = feeds.offering(session.offering.id)
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
        if (update.intent != null && update.intent !in setOf("preview", "watch")) throw ApiFailure(400, "INVALID_REQUEST", "Invalid playback intent")
        update.intent?.let { session.intent = it }
        session.expires = clock.instant().plus(lease(session.intent))
        session.descriptor()
    }
    /** Watch leases outlast the client's 30-second renewal twice over; abandoned previews free their slot sooner. */
    private fun lease(intent: String): Duration = if (intent == "watch") Duration.ofSeconds(90) else Duration.ofSeconds(45)
    suspend fun release(id: String) = mutex.withLock { sessions.remove(id)?.cancel(); Unit }
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
        feeds.recordProtocol(offeringId, protocol)
    }
    suspend fun expire() = mutex.withLock { reap() }
    suspend fun close() = mutex.withLock { sessions.values.forEach { it.cancel() }; sessions.clear() }
    suspend fun acquire(id: String, epoch: Int, token: String, job: Job): MediaResource = mutex.withLock {
        val session = active(id)
        if (epoch != session.epoch) throw ApiFailure(410, "STREAM_CHANGED", "Fetch the current session descriptor", true)
        if (feeds.offering(session.offering.id) == null || store.health(session.offering.id)?.let(::eligible) != true) {
            session.cancel()
            throw ApiFailure(503, "SOURCE_UNAVAILABLE", "Source is no longer eligible", true)
        }
        val url = session.resources[token] ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND", "Unknown media resource")
        val shared = session.offering.protocol == "mpegts" && url == session.offering.url
        // A player reconnecting to a live stream replaces its previous request rather than adding to it.
        if (shared) session.cancel()
        if (session.requests.size >= 12) throw ApiFailure(429, "CAPACITY_EXHAUSTED", "Too many media requests", true)
        session.requests += job
        MediaResource(session.offering, url, shared)
    }
    suspend fun finished(id: String, job: Job) = mutex.withLock { sessions[id]?.requests?.remove(job); Unit }
    suspend fun resourcePath(id: String, epoch: Int, url: String): String = mutex.withLock {
        val session = active(id)
        if (session.epoch != epoch) throw ApiFailure(410, "STREAM_CHANGED", "Fetch current descriptor", true)
        "/media/sessions/$id/$epoch/${session.resource(url)}"
    }
    private suspend fun select(channelId: String): Offering {
        val candidates = feeds.offerings().filter { it.channelId == channelId }
        if (candidates.isEmpty()) throw ApiFailure(404, "CHANNEL_NOT_FOUND", "Unknown channel")
        val states = store.allHealth()
        return candidates.sortedBy { selectionRank(states[it.id]?.status) }
            .firstOrNull { states[it.id]?.let(::eligible) == true }
            ?: throw ApiFailure(503, "SOURCE_UNAVAILABLE", "No eligible source", true)
    }
    private fun active(id: String): LiveSession {
        reap()
        return sessions[id] ?: throw ApiFailure(410, "SESSION_EXPIRED", "Session was released or expired")
    }
    private fun reap() {
        val now = clock.instant()
        val expired = sessions.values.filter { it.expires <= now }
        expired.forEach { it.cancel(); sessions.remove(it.id) }
    }
}
fun eligible(state: dev.jamesrich.sofaking.backend.health.HealthState): Boolean = !state.manuallyDisabled && state.status in setOf(HealthStatus.UNVERIFIED, HealthStatus.HEALTHY, HealthStatus.SUSPECT)

/** Healthy first, then unverified, then suspect; anything else is never selected. */
internal fun selectionRank(status: HealthStatus?): Int =
    when (status) {
        HealthStatus.HEALTHY -> 0
        HealthStatus.UNVERIFIED -> 1
        HealthStatus.SUSPECT -> 2
        else -> 3
    }
